package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 销售模块 Tool 的只读查询。
 * <p>
 * <b>刻意不写 ent_code、也不加 {@code @InterceptorIgnore}</b>：租户条件交给插件自动注入。
 * 这样就没有「手写条件漏写 → 静默跨租户泄漏」的可能。方法是 M3 定下的 Tool 层隔离策略：
 * 默认交给插件，只有实测插件改写有问题的语句才方法级关插件 + 显式 ent_code。
 * <p>
 * 其中 {@link #selectOrderDetail} 是 JOIN，属于需要实测确认的语句——
 * 若插件改写有误，改为方法级 {@code @InterceptorIgnore} + 手写 ent_code，
 * 并按约定补一条跨租户泄漏测试。
 * <p>
 * 返回 {@code List<Map>} 而非具名 VO：结果是交给模型读的，不是对外 API 响应；
 * 且 PG 列名本就是小写 snake_case，直接用列名作 key 无需别名映射。
 * 注意 <b>PG 会把未加双引号的别名折叠成小写</b>，故别名一律写成 snake_case。
 *
 * <h3>日期列必须 TO_CHAR</h3>
 * DATE 列若直接 select，JDBC 会返成 {@code java.sql.Date}（继承 {@code java.util.Date}），
 * JSON 序列化后变成 ISO <b>时刻</b>——本地零点的日期被时区位移，例如
 * {@code 2026-09-11} 输出成 {@code 2026-09-10T16:00:00.000Z}。
 * 模型据此回答会<b>错一天</b>，且不会有任何报错。
 * 故所有面向模型的日期列一律 {@code TO_CHAR(col, 'YYYY-MM-DD')}，
 * 格式由我们完全掌控，模型直接拿到 {@code 2026-09-11}。
 * {@code ToolQueryIntegrationTest} 有一条断言守着这个格式，漏加 TO_CHAR 会立刻失败。
 */
public interface SalesToolMapper {

    /** 按客户名称模糊查订单表头。返回行数上限 20，防止结果撑爆模型上下文。 */
    @Select("""
            SELECT order_no, TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date,
                   customer_name, total_amount, status
            FROM sales_order
            WHERE customer_name LIKE #{pattern}
            ORDER BY order_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectOrdersByCustomer(@Param("pattern") String pattern);

    /**
     * 按订单号查订单表头 + 明细行。
     * <p>
     * JOIN 的 ON 里带上 {@code ent_code} 是<b>行匹配正确性</b>要求，与租户过滤无关：
     * 只按 order_no 关联时，不同租户若存在同号订单会互相串行。
     */
    @Select("""
            SELECT o.order_no, TO_CHAR(o.order_date, 'YYYY-MM-DD') AS order_date,
                   o.customer_name, o.status,
                   d.product_name, d.quantity, d.unit_price, d.amount
            FROM sales_order o
            JOIN sales_order_item d
              ON d.ent_code = o.ent_code
             AND d.order_no = o.order_no
            WHERE o.order_no = #{orderNo}
            ORDER BY d.id
            LIMIT 50
            """)
    List<Map<String, Object>> selectOrderDetail(@Param("orderNo") String orderNo);

    /** 按订单号查发货与物流信息。 */
    @Select("""
            SELECT shipment_no, order_no,
                   TO_CHAR(shipment_date, 'YYYY-MM-DD') AS shipment_date,
                   quantity, logistics_no, status
            FROM shipment
            WHERE order_no = #{orderNo}
            ORDER BY shipment_date DESC
            """)
    List<Map<String, Object>> selectShipmentByOrder(@Param("orderNo") String orderNo);

    /**
     * 按客户查应收账款汇总。
     * <p>
     * 余额字段一律用 {@code COALESCE} 包住：没有明细行时 {@code SUM} 返回 NULL，
     * 模型拿到 null 会当成「数据缺失」而不是「0 元」。
     */
    @Select("""
            SELECT customer_name,
                   COALESCE(SUM(due_amount), 0)                       AS total_due,
                   COALESCE(SUM(received_amount), 0)                  AS total_received,
                   COALESCE(SUM(due_amount - received_amount), 0)     AS balance
            FROM accounts_receivable
            WHERE customer_name LIKE #{pattern}
            GROUP BY customer_name
            ORDER BY balance DESC
            """)
    List<Map<String, Object>> selectReceivableByCustomer(@Param("pattern") String pattern);

    /** 按时间范围查订单表头，支撑「最近 / 本周 / 本月 / 今年」这类提问。 */
    @Select("""
            SELECT order_no, TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date,
                   customer_name, total_amount, status
            FROM sales_order
            WHERE order_date BETWEEN #{from} AND #{to}
            ORDER BY order_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectOrdersByDateRange(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);

    /** 按时间范围做销售汇总。 */
    @Select("""
            SELECT COUNT(*)                              AS order_count,
                   COALESCE(SUM(total_amount), 0)        AS total_amount,
                   COUNT(DISTINCT customer_id)           AS customer_count
            FROM sales_order
            WHERE order_date BETWEEN #{from} AND #{to}
            """)
    List<Map<String, Object>> selectSalesSummary(@Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);

}
