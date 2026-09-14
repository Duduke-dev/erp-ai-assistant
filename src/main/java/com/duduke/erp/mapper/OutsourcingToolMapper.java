package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 委外模块 Tool 的只读查询。
 * <p>
 * 约定同 {@link SalesToolMapper}：不写 {@code ent_code}、不加 {@code @InterceptorIgnore}，
 * 日期列 {@code TO_CHAR} 成 {@code yyyy-MM-dd}。
 */
public interface OutsourcingToolMapper {

    /** 按供应商名称模糊查委外加工订单。 */
    @Select("""
            SELECT order_no, supplier_name, product_name, quantity,
                   TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date, status
            FROM outsourcing_order
            WHERE supplier_name LIKE #{pattern}
            ORDER BY order_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectOrdersBySupplier(@Param("pattern") String pattern);

    /** 按委外单号查订单详情。 */
    @Select("""
            SELECT order_no, supplier_name, product_name, quantity,
                   TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date, status
            FROM outsourcing_order
            WHERE order_no = #{orderNo}
            """)
    List<Map<String, Object>> selectOrderByNo(@Param("orderNo") String orderNo);

    /** 按下单日期范围查委外订单。 */
    @Select("""
            SELECT order_no, supplier_name, product_name, quantity,
                   TO_CHAR(order_date, 'YYYY-MM-DD') AS order_date, status
            FROM outsourcing_order
            WHERE order_date BETWEEN #{from} AND #{to}
            ORDER BY order_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectOrdersByDateRange(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);

    /** 按委外单号查来料 / 退料流水。 */
    @Select("""
            SELECT order_no, product_name, flow_type, quantity,
                   TO_CHAR(flow_date, 'YYYY-MM-DD') AS flow_date
            FROM outsourcing_material_flow
            WHERE order_no = #{orderNo}
            ORDER BY flow_date, id
            LIMIT 50
            """)
    List<Map<String, Object>> selectMaterialFlow(@Param("orderNo") String orderNo);

}
