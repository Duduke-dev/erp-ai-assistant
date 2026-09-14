package com.duduke.erp.service.tool.dynamic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 动态 SQL 的租户条件注入器。
 * <p>
 * 动态 Tool 的 SQL 来自管理端配置，<b>不带 {@code ent_code} 条件</b>
 * （也不该让配置者去写——写漏了就是跨租户泄漏）。租户隔离在这里补上。
 *
 * <h3>为什么必须给原 WHERE 条件加括号（防 OR 优先级绕过）</h3>
 * 假设模板是：
 * <pre>
 * SELECT * FROM sales_order WHERE customer_name = :name OR status = 'draft'
 * </pre>
 * 若简单地追加 {@code AND ent_code = ?}：
 * <pre>
 * ... WHERE customer_name = ? OR status = 'draft' AND ent_code = ?
 * </pre>
 * 因为 {@code AND} 的优先级<b>高于</b> {@code OR}，实际等价于：
 * <pre>
 * ... WHERE customer_name = ? OR (status = 'draft' AND ent_code = ?)
 * </pre>
 * 前半支完全没有租户限制 → <b>把所有租户的订单都查了出来，且不报错</b>。
 * <p>
 * 正确做法是把原条件整体包起来：
 * <pre>
 * ... WHERE (customer_name = ? OR status = 'draft') AND ent_code = ?
 * </pre>
 * 这就是本类存在的首要理由。{@code TenantSqlInjectorTest} 里有专门守住它的用例。
 *
 * <h3>参数下标为什么不能是「追加到末尾」</h3>
 * 注入的 {@code ?} 落在原 SQL 的 {@code ?} <b>之后</b>、尾部子句之前，
 * 所以租户实参要插在「插入点之前的 {@code ?} 个数」那个下标处。
 * 简单追加到实参列表末尾，在模板带尾部子句参数时就会错位。
 */
@Component
public class TenantSqlInjector {

    /** 尾部子句：租户条件要插到它们之前，否则语法错误 */
    private static final Pattern TAIL_CLAUSE_PATTERN =
            Pattern.compile("\\b(ORDER\\s+BY|GROUP\\s+BY|LIMIT)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern WHERE_PATTERN =
            Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /** 只注入条件，不关心参数下标 */
    public String inject(String sql, String tableAlias) {
        return injectWithParameterIndex(sql, tableAlias).sql();
    }

    /**
     * 注入 {@code ent_code = ?} 并给出租户实参应插入的下标。
     *
     * @param sql        已完成命名参数绑定的 SQL
     * @param tableAlias 主表别名；为空时用裸列名。JOIN 查询必须传别名，否则列有歧义
     */
    public TenantSqlInjection injectWithParameterIndex(String sql, String tableAlias) {
        String normalized = normalizeWhitespace(sql);
        String column = tableAlias == null || tableAlias.isBlank()
                ? "ent_code"
                : tableAlias.trim() + ".ent_code";

        int insertPosition = findTailClausePosition(normalized);
        String prefix = insertPosition >= 0
                ? normalized.substring(0, insertPosition).stripTrailing()
                : normalized;
        String suffix = insertPosition >= 0
                ? normalized.substring(insertPosition).stripLeading()
                : "";

        Matcher where = WHERE_PATTERN.matcher(prefix);
        String injected;
        if (where.find()) {
            // 关键：把原 WHERE 之后的条件整段括号包住，再 AND 上租户条件。
            // 少了这对括号，条件里只要有 OR 就会绕过租户隔离（见类注释）。
            String head = prefix.substring(0, where.start()).stripTrailing();
            String condition = prefix.substring(where.end()).strip();
            injected = head + " WHERE (" + condition + ") AND " + column + " = ?";
        }
        else {
            injected = prefix + " WHERE " + column + " = ?";
        }
        injected = suffix.isEmpty() ? injected : injected + " " + suffix;

        // 下标 = 插入点之前的 ? 个数。注意必须是 prefix 而不是 injected——
        // injected 里已经含我们刚加的那个 ?，用它会把下标算多一位。
        return new TenantSqlInjection(injected, countJdbcParameters(prefix));
    }

    /**
     * 压缩空白。多行 SQL 模板会影响尾部子句定位与拼接，先归一成单行。
     */
    public String normalizeWhitespace(String sql) {
        return sql.trim().replaceAll("\\s+", " ");
    }

    /** 是否已有 WHERE 子句 */
    public boolean hasWhereClause(String sql) {
        return WHERE_PATTERN.matcher(sql).find();
    }

    /**
     * 最早的尾部子句位置，不存在返回 -1。
     */
    public int findTailClausePosition(String sql) {
        Matcher matcher = TAIL_CLAUSE_PATTERN.matcher(sql.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.start() : -1;
    }

    /**
     * 统计 JDBC 参数占位符个数，<b>忽略字符串字面量里的问号</b>。
     * <p>
     * 模板里可能出现 {@code WHERE remark LIKE '%?%'} 这种写法，
     * 若不加区分地把 {@code ?} 都算上，租户实参的下标就会偏大，导致参数错位。
     * 单引号内的 {@code ''} 是转义的单引号（SQL 标准），需要跳过两个字符。
     */
    public int countJdbcParameters(String sql) {
        int count = 0;
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                if (inQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && ch == '?') {
                count++;
            }
        }
        return count;
    }

}
