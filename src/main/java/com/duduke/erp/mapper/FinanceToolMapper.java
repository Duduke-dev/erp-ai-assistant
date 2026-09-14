package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 财务模块 Tool 的只读查询。
 * <p>
 * 约定同 {@link SalesToolMapper}：不写 {@code ent_code}、不加 {@code @InterceptorIgnore}，
 * 日期列 {@code TO_CHAR} 成 {@code yyyy-MM-dd}。
 * <p>
 * 应收 / 应付分别归在销售与采购模块（它们的业务归属更贴切），此处只做总账与收付款。
 */
public interface FinanceToolMapper {

    /** 按月份（{@code yyyy-MM}）统计收入、支出与净额。 */
    @Select("""
            SELECT TO_CHAR(ledger_date, 'YYYY-MM')                  AS month,
                   COALESCE(SUM(income_amount), 0)                  AS total_income,
                   COALESCE(SUM(expense_amount), 0)                 AS total_expense,
                   COALESCE(SUM(income_amount - expense_amount), 0) AS net_amount
            FROM finance_ledger
            WHERE TO_CHAR(ledger_date, 'YYYY-MM') = #{month}
            GROUP BY TO_CHAR(ledger_date, 'YYYY-MM')
            """)
    List<Map<String, Object>> selectMonthlySummary(@Param("month") String month);

    /** 按会计科目模糊查总账明细。 */
    @Select("""
            SELECT TO_CHAR(ledger_date, 'YYYY-MM-DD') AS ledger_date, account_item,
                   income_amount, expense_amount, counterparty, remark
            FROM finance_ledger
            WHERE account_item LIKE #{pattern}
            ORDER BY ledger_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectLedgerByAccount(@Param("pattern") String pattern);

    /** 按时间范围查总账明细。 */
    @Select("""
            SELECT TO_CHAR(ledger_date, 'YYYY-MM-DD') AS ledger_date, account_item,
                   income_amount, expense_amount, counterparty
            FROM finance_ledger
            WHERE ledger_date BETWEEN #{from} AND #{to}
            ORDER BY ledger_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectLedgerByDateRange(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);

    /** 按客户名称模糊查收款记录。 */
    @Select("""
            SELECT payment_no, customer_name, order_no,
                   TO_CHAR(payment_date, 'YYYY-MM-DD') AS payment_date,
                   amount, payment_method
            FROM payment_record
            WHERE customer_name LIKE #{pattern}
            ORDER BY payment_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectPaymentsByCustomer(@Param("pattern") String pattern);

    /** 按收款日期范围查收款记录。 */
    @Select("""
            SELECT payment_no, customer_name, order_no,
                   TO_CHAR(payment_date, 'YYYY-MM-DD') AS payment_date,
                   amount, payment_method
            FROM payment_record
            WHERE payment_date BETWEEN #{from} AND #{to}
            ORDER BY payment_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectPaymentsByDateRange(@Param("from") LocalDate from,
                                                        @Param("to") LocalDate to);

}
