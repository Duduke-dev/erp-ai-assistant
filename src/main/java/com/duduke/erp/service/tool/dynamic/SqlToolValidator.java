package com.duduke.erp.service.tool.dynamic;

import java.util.Set;
import java.util.regex.Pattern;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.service.tool.ToolNames;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 动态 SQL Tool 的安全校验器 —— 模型生成的 SQL 直连数据库前的唯一闸门。
 * <p>
 * 校验分两层，缺一不可：
 * <ol>
 *   <li><b>配置时</b>（管理端保存）：拦住明显违规的配置，给人即时反馈；</li>
 *   <li><b>执行前</b>（每次调用）：再过一遍。配置存库后可能被直接改库绕过，
 *       执行期校验才是真正的防线。</li>
 * </ol>
 *
 * <h3>只放行单层 SELECT</h3>
 * 租户条件注入只处理<b>第一个 WHERE</b>，因此子查询、CTE、联合查询一律禁止——
 * 它们会让注入的条件落错位置，把租户隔离绕过。这不是保守，是注入器的固有边界。
 * <p>
 * 参考实现先允许 {@code with} 开头、又用另一条规则禁掉，语义绕。
 * 这里直接要求 {@code select} 开头，错误信息更明确。
 *
 * <h3>关键词检测不区分注释与字符串</h3>
 * 只要 SQL 文本里出现 {@code drop} 之类的词就拒绝，即使它写在注释或字符串字面量里。
 * 这是<b>故意 fail-closed</b>：误伤一条合法配置的代价，远低于放过一条写操作。
 */
@Component
@RequiredArgsConstructor
public class SqlToolValidator {

    private final ObjectMapper objectMapper;

    private final SqlTemplateBinder binder;

    /** Tool 名称必须可被模型与 Spring AI 稳定识别 */
    private static final Pattern TOOL_NAME_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /** 主表别名只能是安全的 SQL 标识符，否则拼进 ent_code 条件会引入非法片段 */
    private static final Pattern TABLE_ALIAS_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 写操作与 DDL 关键字一律禁止。
     * <p>
     * <b>{@code into} 是本项目相对参考实现补上的</b>：参考实现的黑名单里没有它，
     * 于是 {@code SELECT code INTO stolen FROM product} 能通过它的全套校验
     * （SELECT 开头、无任何被禁词、不是子查询）——而这句会<b>建表</b>。
     * 查询语句永远不需要 {@code INTO}，直接禁掉。
     */
    private static final Pattern FORBIDDEN_SQL_PATTERN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|grant|revoke|into)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 入口必须是 SELECT */
    private static final Pattern READ_ONLY_SQL_PATTERN =
            Pattern.compile("^\\s*select\\b", Pattern.CASE_INSENSITIVE);

    /** 多查询块：租户注入只处理第一个 WHERE，这些形态会让注入落错位置 */
    private static final Pattern UNSUPPORTED_MULTI_QUERY_PATTERN = Pattern.compile(
            "\\b(union|intersect|except)\\b|^\\s*with\\b|\\(\\s*select\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 校验完整的动态 Tool 配置。
     *
     * @throws IllegalArgumentException 任一项不合法
     */
    public void validateTool(LlmTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool 配置不能为空");
        }
        validateToolName(tool.getToolName());
        if (tool.getToolDesc() == null || tool.getToolDesc().isBlank()) {
            throw new IllegalArgumentException("Tool 描述不能为空");
        }
        validateInputSchema(tool.getInputSchema());
        validateSql(tool.getSqlTemplate());
        validateResultLimit(tool.getResultLimit());
        validateTableAlias(tool.getTableAlias());
        validateTemplateParameters(tool.getSqlTemplate(), tool.getInputSchema());
    }

    /**
     * 交叉校验：模板引用的每个 {@code :name} 都必须在入参 Schema 里声明过。
     * <p>
     * 放在配置时做，是为了让录入错误<b>当场</b>暴露。只靠执行期的绑定器也能发现，
     * 但那要等到模型第一次调用这个 Tool 才报警——期间它就是一颗哑弹。
     */
    public void validateTemplateParameters(String sqlTemplate, String inputSchema) {
        Set<String> declared = this.binder.extractDeclaredParameters(inputSchema);
        for (String name : this.binder.extractPlaceholders(sqlTemplate)) {
            if (!declared.contains(name)) {
                throw new IllegalArgumentException(
                        "SQL 模板引用了未在入参 Schema 中声明的参数：" + name);
            }
        }
    }

    /**
     * 校验动态 SQL 是单条只读查询。
     */
    public void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 模板不能为空");
        }
        String trimmed = sql.trim();
        // 分号一律拒绝：多语句执行是 SQL 注入最经典的入口
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException("SQL 模板不允许包含分号");
        }
        if (!READ_ONLY_SQL_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("只允许配置以 SELECT 开头的只读查询");
        }
        if (FORBIDDEN_SQL_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("SQL 中包含被禁止的写操作或 DDL 关键字");
        }
        if (UNSUPPORTED_MULTI_QUERY_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException(
                    "动态 SQL 只支持单层 SELECT：不支持 CTE、子查询与 union/intersect/except");
        }
    }

    /**
     * 校验 Tool 名称格式与保留字。
     */
    public void validateToolName(String toolName) {
        if (toolName == null || !TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException(
                    "Tool 名称只能包含字母、数字和下划线，且须以字母或下划线开头，长度不超过 64");
        }
        // 动态 Tool 不得占用系统内部 Tool 名称，否则模型会收到两个同名函数，行为不可预期
        if (ToolNames.isReserved(toolName)) {
            throw new IllegalArgumentException("Tool 名称 " + toolName + " 为系统保留名称");
        }
    }

    /**
     * 校验入参 JSON Schema 是合法 JSON 对象。
     * <p>
     * 只校验到「是 JSON 对象」这一层——Schema 的语义正确性由管理端录入时把关，
     * 执行期再深校验收益有限，反而会因 Schema 方言差异误伤。
     */
    public void validateInputSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("Tool 入参 Schema 不能为空");
        }
        try {
            if (!this.objectMapper.readTree(inputSchema).isObject()) {
                throw new IllegalArgumentException("Tool 入参 Schema 必须是 JSON 对象");
            }
        }
        catch (IllegalArgumentException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("Tool 入参 Schema 不是合法 JSON", e);
        }
    }

    /**
     * 校验返回行数上限。为空表示用默认值。
     */
    public void validateResultLimit(Integer resultLimit) {
        if (resultLimit != null && (resultLimit < 1 || resultLimit > 500)) {
            throw new IllegalArgumentException("Tool 返回行数必须在 1 到 500 之间");
        }
    }

    /**
     * 校验主表别名格式。为空表示注入时不加前缀（裸 {@code ent_code}）。
     */
    public void validateTableAlias(String tableAlias) {
        if (tableAlias != null && !tableAlias.isBlank()
                && !TABLE_ALIAS_PATTERN.matcher(tableAlias.trim()).matches()) {
            throw new IllegalArgumentException(
                    "主表别名只能包含字母、数字和下划线，且须以字母或下划线开头");
        }
    }

}
