package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 售后模块 Tool 的只读查询。
 * <p>
 * 约定同 {@link SalesToolMapper}：不写 {@code ent_code}、不加 {@code @InterceptorIgnore}，
 * 日期列 {@code TO_CHAR} 成 {@code yyyy-MM-dd}。
 */
public interface AfterSalesToolMapper {

    /** 按客户名称模糊查售后工单。 */
    @Select("""
            SELECT ticket_no, customer_name, product_name, problem_type,
                   TO_CHAR(ticket_date, 'YYYY-MM-DD') AS ticket_date,
                   handle_status, description
            FROM after_sales_ticket
            WHERE customer_name LIKE #{pattern}
            ORDER BY ticket_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectTicketsByCustomer(@Param("pattern") String pattern);

    /** 按工单号查售后工单详情。 */
    @Select("""
            SELECT ticket_no, customer_name, product_name, problem_type,
                   TO_CHAR(ticket_date, 'YYYY-MM-DD') AS ticket_date,
                   handle_status, description
            FROM after_sales_ticket
            WHERE ticket_no = #{ticketNo}
            """)
    List<Map<String, Object>> selectTicketByNo(@Param("ticketNo") String ticketNo);

    /** 按工单日期范围查售后工单。 */
    @Select("""
            SELECT ticket_no, customer_name, product_name, problem_type,
                   TO_CHAR(ticket_date, 'YYYY-MM-DD') AS ticket_date,
                   handle_status
            FROM after_sales_ticket
            WHERE ticket_date BETWEEN #{from} AND #{to}
            ORDER BY ticket_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectTicketsByDateRange(@Param("from") LocalDate from,
                                                       @Param("to") LocalDate to);

    /** 按产品名称模糊查退换货记录。 */
    @Select("""
            SELECT return_no, order_no, customer_name, product_name, return_type, quantity,
                   TO_CHAR(return_date, 'YYYY-MM-DD') AS return_date, reason
            FROM return_order
            WHERE product_name LIKE #{pattern}
            ORDER BY return_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectReturnsByProduct(@Param("pattern") String pattern);

}
