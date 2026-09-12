package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.duduke.erp.entity.vo.CustomerSalesVO;
import com.duduke.erp.entity.vo.MonthlySalesVO;
import com.duduke.erp.entity.vo.ProductSalesVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 销售统计查询。
 * <p>
 * 这些语句带 JOIN 与 GROUP BY，租户插件改写复杂 SQL 容易出问题，
 * 因此统一关闭插件，改由 SQL 显式带 ent_code 条件，
 * 与 {@code SysUserMapper#selectForLogin} 的处理方式保持一致。
 */
@InterceptorIgnore(tenantLine = "true")
public interface SalesStatsMapper {

    @Select("""
            SELECT customer_id,
                   MAX(customer_name) AS customer_name,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount
            FROM sales_order
            WHERE ent_code = #{entCode}
              AND order_date BETWEEN #{from} AND #{to}
            GROUP BY customer_id
            ORDER BY total_amount DESC
            """)
    List<CustomerSalesVO> byCustomer(@Param("entCode") String entCode,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Select("""
            SELECT i.product_id,
                   MAX(i.product_name) AS product_name,
                   COALESCE(SUM(i.quantity), 0) AS total_quantity,
                   COALESCE(SUM(i.amount), 0) AS total_amount
            FROM sales_order_item i
            JOIN sales_order o
              ON o.ent_code = i.ent_code
             AND o.order_no = i.order_no
            WHERE i.ent_code = #{entCode}
              AND o.order_date BETWEEN #{from} AND #{to}
            GROUP BY i.product_id
            ORDER BY total_amount DESC
            """)
    List<ProductSalesVO> byProduct(@Param("entCode") String entCode,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    @Select("""
            SELECT TO_CHAR(order_date, 'YYYY-MM') AS month,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount
            FROM sales_order
            WHERE ent_code = #{entCode}
              AND order_date BETWEEN #{from} AND #{to}
            GROUP BY TO_CHAR(order_date, 'YYYY-MM')
            ORDER BY month
            """)
    List<MonthlySalesVO> monthly(@Param("entCode") String entCode,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

}
