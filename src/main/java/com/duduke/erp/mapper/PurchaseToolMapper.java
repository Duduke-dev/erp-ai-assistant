package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 采购模块 Tool 的只读查询。
 * <p>
 * 约定同 {@link SalesToolMapper}：<b>不写 {@code ent_code}、不加 {@code @InterceptorIgnore}</b>
 * （租户条件交插件自动注入），<b>日期列一律 {@code TO_CHAR} 成 {@code yyyy-MM-dd}</b>
 * （DATE 直接 select 会被序列化成 ISO 时刻导致错一天），别名一律 snake_case。
 */
public interface PurchaseToolMapper {

    /** 按供应商名称模糊查采购订单。 */
    @Select("""
            SELECT order_no, TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date,
                   supplier_name, total_amount, status
            FROM purchase_order
            WHERE supplier_name LIKE #{pattern}
            ORDER BY order_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectOrdersBySupplier(@Param("pattern") String pattern);

    /** 按采购单号查订单表头 + 物料明细。ON 带 ent_code 防跨租户同号订单串行。 */
    @Select("""
            SELECT o.order_no, TO_CHAR(o.order_date, 'YYYY-MM-DD') AS order_date,
                   o.supplier_name, o.status,
                   d.product_name, d.quantity, d.received_qty, d.unit_price, d.amount
            FROM purchase_order o
            JOIN purchase_order_item d
              ON d.ent_code = o.ent_code
             AND d.order_no = o.order_no
            WHERE o.order_no = #{orderNo}
            ORDER BY d.id
            LIMIT 50
            """)
    List<Map<String, Object>> selectOrderDetail(@Param("orderNo") String orderNo);

    /** 按采购单号查到货收货记录。 */
    @Select("""
            SELECT receive_no, order_no, product_name,
                   TO_CHAR(receive_date, 'YYYY-MM-DD') AS receive_date, quantity
            FROM purchase_receive
            WHERE order_no = #{orderNo}
            ORDER BY receive_date DESC
            """)
    List<Map<String, Object>> selectReceiveByOrder(@Param("orderNo") String orderNo);

    /** 按时间范围查采购订单。 */
    @Select("""
            SELECT order_no, TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date,
                   supplier_name, total_amount, status
            FROM purchase_order
            WHERE order_date BETWEEN #{from} AND #{to}
            ORDER BY order_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectOrdersByDateRange(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);

    /** 按供应商查应付账款汇总。余额用 COALESCE 兜住无明细时的 NULL。 */
    @Select("""
            SELECT supplier_name,
                   COALESCE(SUM(due_amount), 0)                 AS total_due,
                   COALESCE(SUM(paid_amount), 0)                AS total_paid,
                   COALESCE(SUM(due_amount - paid_amount), 0)   AS balance
            FROM accounts_payable
            WHERE supplier_name LIKE #{pattern}
            GROUP BY supplier_name
            ORDER BY balance DESC
            """)
    List<Map<String, Object>> selectPayableBySupplier(@Param("pattern") String pattern);

}
