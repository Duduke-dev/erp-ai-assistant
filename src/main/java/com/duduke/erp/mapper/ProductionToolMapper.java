package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 生产模块 Tool 的只读查询。
 * <p>
 * 约定同 {@link SalesToolMapper}：不写 {@code ent_code}、不加 {@code @InterceptorIgnore}，
 * 日期列 {@code TO_CHAR} 成 {@code yyyy-MM-dd}。
 */
public interface ProductionToolMapper {

    /** 按工单号查生产进度（计划 / 完成 / 报废数量）。 */
    @Select("""
            SELECT order_no, product_name, planned_qty, completed_qty, scrap_qty, status,
                   TO_CHAR(plan_start_date, 'YYYY-MM-DD') AS plan_start_date,
                   TO_CHAR(plan_end_date, 'YYYY-MM-DD')   AS plan_end_date
            FROM work_order
            WHERE order_no = #{orderNo}
            """)
    List<Map<String, Object>> selectWorkOrderStatus(@Param("orderNo") String orderNo);

    /** 按产品名称模糊查生产工单列表。 */
    @Select("""
            SELECT order_no, product_name, planned_qty, completed_qty, scrap_qty, status,
                   TO_CHAR(plan_start_date, 'YYYY-MM-DD') AS plan_start_date,
                   TO_CHAR(plan_end_date, 'YYYY-MM-DD')   AS plan_end_date
            FROM work_order
            WHERE product_name LIKE #{pattern}
            ORDER BY plan_start_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectOrdersByProduct(@Param("pattern") String pattern);

    /** 按工单号查用料领料情况。 */
    @Select("""
            SELECT order_no, product_name, required_qty, issued_qty
            FROM work_order_material
            WHERE order_no = #{orderNo}
            ORDER BY id
            LIMIT 50
            """)
    List<Map<String, Object>> selectMaterialsByOrder(@Param("orderNo") String orderNo);

    /** 按计划开工日期范围查工单。 */
    @Select("""
            SELECT order_no, product_name, planned_qty, completed_qty, status,
                   TO_CHAR(plan_start_date, 'YYYY-MM-DD') AS plan_start_date
            FROM work_order
            WHERE plan_start_date BETWEEN #{from} AND #{to}
            ORDER BY plan_start_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectOrdersByDateRange(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);

    /** 按工单号查工序与报工状态，按工序号升序。 */
    @Select("""
            SELECT order_no, step_no, step_name, work_center, status
            FROM work_order_routing
            WHERE order_no = #{orderNo}
            ORDER BY step_no
            """)
    List<Map<String, Object>> selectRoutingByOrder(@Param("orderNo") String orderNo);

}
