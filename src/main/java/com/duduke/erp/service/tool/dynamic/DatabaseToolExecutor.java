package com.duduke.erp.service.tool.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * 动态 SQL Tool 执行器 —— 唯一真正碰数据库的地方。
 * <p>
 * 执行顺序（每一步都不可省）：
 * <ol>
 *   <li><b>再校验一次</b>：配置存库后可能被直接改库绕过管理端校验，
 *       执行期这道才是真防线；</li>
 *   <li>{@code requireEntCode()}：拿不到租户就<b>直接失败</b>，
 *       绝不放行一条没有租户条件的查询；</li>
 *   <li>绑定命名参数 → 注入租户条件 → 套行数上限；</li>
 *   <li>在<b>只读事务</b>里执行。</li>
 * </ol>
 *
 * <h3>只读事务是相对参考实现的补强</h3>
 * 参考实现只靠 {@link SqlToolValidator} 的入口白名单，<b>没有任何只读事务或连接只读设置</b>。
 * 这是真实缺口——关键词黑名单是<b>枚举式</b>的，永远可能漏：
 * <ul>
 *   <li>{@code SELECT code INTO stolen FROM product} 会<b>建表</b>：
 *       它是 SELECT 开头、不含任何被禁词、不是子查询，能通过参考实现的全套校验。
 *       （本项目已把 {@code into} 加入禁用词，见 {@code SqlToolValidator}。）</li>
 *   <li>调用一个内部会写库的存储函数，形如 {@code SELECT some_writing_func()}——
 *       函数名不含任何被禁词，校验器无从判断，只能靠数据库拦。</li>
 * </ul>
 * 枚举挡不全，但数据库挡得住。这是<b>兜底</b>而非替代——两层都要有。
 * <p>
 * 用 {@code SET TRANSACTION READ ONLY} 而非连接级 {@code setReadOnly}：
 * 前者只作用于当前事务，不会把只读状态留在连接池的连接上影响后续使用。
 * 事务用 {@code REQUIRES_NEW} 新开，保证 {@code SET TRANSACTION} 是本事务的第一条语句
 * （PG 要求它必须在任何查询之前执行）。
 * <p>
 * <b>注意 PG 的一个反直觉点</b>：只读事务<b>并不</b>阻止 {@code nextval}——
 * 操作序列被 PG 视为可接受。别指望它挡住这类副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseToolExecutor {

    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

    /** 未配置上限时的默认返回行数 */
    private static final int DEFAULT_RESULT_LIMIT = 50;

    private final JdbcTemplate jdbcTemplate;

    private final PlatformTransactionManager transactionManager;

    private final SqlToolValidator validator;

    private final SqlTemplateBinder binder;

    private final TenantSqlInjector tenantSqlInjector;

    /**
     * 执行动态 Tool 查询。
     *
     * @param tool      动态 Tool 定义
     * @param toolInput 模型传入的参数 JSON
     * @return 查询结果
     */
    public ToolQueryResult execute(LlmTool tool, String toolInput) {
        this.validator.validateTool(tool);
        String entCode = TenantContext.requireEntCode();

        BoundSql bound = this.binder.bind(tool.getSqlTemplate(), tool.getInputSchema(), toolInput);
        TenantSqlInjection injection = this.tenantSqlInjector.injectWithParameterIndex(
                bound.sql(), tool.getTableAlias());
        String sql = applyResultLimit(injection.sql(), tool.getResultLimit());

        List<Object> arguments = new ArrayList<>(bound.arguments());
        // 按算出的下标插入，不能追加到末尾——尾部子句里可能还有参数
        arguments.add(injection.tenantParameterIndex(), entCode);

        List<Map<String, Object>> rows = queryReadOnly(sql, arguments);
        return new ToolQueryResult(rows);
    }

    /**
     * 在只读事务中执行查询。
     * <p>
     * 只读不是「性能优化」，是<b>安全兜底</b>：即使校验器放过了某条能改数据的语句
     * （{@code SELECT FOR UPDATE}、调用有副作用的函数等），数据库也会拒绝执行。
     */
    private List<Map<String, Object>> queryReadOnly(String sql, List<Object> arguments) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = this.transactionManager.getTransaction(definition);
        try {
            // 必须是本事务的第一条语句，否则 PG 会拒绝
            this.jdbcTemplate.execute("SET TRANSACTION READ ONLY");
            List<Map<String, Object>> rows =
                    this.jdbcTemplate.queryForList(sql, arguments.toArray());
            this.transactionManager.commit(status);
            return rows;
        }
        catch (RuntimeException e) {
            this.transactionManager.rollback(status);
            // SQL 不带进日志：里面有租户与用户参数，且长度可能很大
            log.warn("动态 Tool 查询失败：{}", e.getMessage());
            throw e;
        }
    }

    /**
     * 套上行数上限。
     * <p>
     * SQL 自带 {@code LIMIT} 时用派生表外包一层，否则直接追加——
     * 直接追加会变成两个 LIMIT 而语法错误。
     */
    public String applyResultLimit(String sql, Integer resultLimit) {
        int limit = resultLimit == null ? DEFAULT_RESULT_LIMIT : resultLimit;
        if (LIMIT_PATTERN.matcher(sql).find()) {
            return "SELECT * FROM (" + sql + ") dynamic_tool_result LIMIT " + limit;
        }
        return sql + " LIMIT " + limit;
    }

}
