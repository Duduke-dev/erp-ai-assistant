package com.duduke.erp.service;

import java.time.LocalDate;
import java.util.List;

import com.duduke.erp.entity.vo.CustomerSalesVO;
import com.duduke.erp.entity.vo.MonthlySalesVO;
import com.duduke.erp.entity.vo.ProductSalesVO;
import com.duduke.erp.mapper.SalesStatsMapper;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/**
 * 销售统计。
 * <p>
 * 统计 SQL 关闭了租户插件、改为显式带 ent_code，所以这里需要从上下文取出后传入。
 * 时间范围可不传，缺省回看 12 个月。
 */
@Service
@RequiredArgsConstructor
public class SalesStatsService {

    /** 未指定起止时间时默认回看的月数 */
    private static final int DEFAULT_MONTHS = 12;

    private final SalesStatsMapper salesStatsMapper;

    /**
     * 按客户汇总销售额。
     */
    public List<CustomerSalesVO> byCustomer(LocalDate from, LocalDate to) {
        return this.salesStatsMapper.byCustomer(TenantContext.requireEntCode(),
                resolveFrom(from), resolveTo(to));
    }

    /**
     * 按产品汇总销量与销售额。
     */
    public List<ProductSalesVO> byProduct(LocalDate from, LocalDate to) {
        return this.salesStatsMapper.byProduct(TenantContext.requireEntCode(),
                resolveFrom(from), resolveTo(to));
    }

    /**
     * 按月汇总销售额趋势。
     */
    public List<MonthlySalesVO> monthly(LocalDate from, LocalDate to) {
        return this.salesStatsMapper.monthly(TenantContext.requireEntCode(),
                resolveFrom(from), resolveTo(to));
    }

    private LocalDate resolveFrom(LocalDate from) {
        return from != null ? from : LocalDate.now().minusMonths(DEFAULT_MONTHS).withDayOfMonth(1);
    }

    private LocalDate resolveTo(LocalDate to) {
        return to != null ? to : LocalDate.now();
    }

}
