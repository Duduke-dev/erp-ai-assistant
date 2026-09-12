package com.duduke.erp.controller;

import java.time.LocalDate;
import java.util.List;

import com.duduke.erp.entity.vo.CustomerSalesVO;
import com.duduke.erp.entity.vo.MonthlySalesVO;
import com.duduke.erp.entity.vo.ProductSalesVO;
import com.duduke.erp.service.SalesStatsService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 销售统计接口。
 * <p>
 * 统计不建表，全部走聚合查询；时间范围缺省为近 12 个月。
 */
@RestController
@RequestMapping("/api/biz/sales_stats")
@RequiredArgsConstructor
public class SalesStatsController {

    private final SalesStatsService salesStatsService;

    @SaCheckPermission("biz:sales:list")
    @GetMapping("/by-customer")
    public List<CustomerSalesVO> byCustomer(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return this.salesStatsService.byCustomer(from, to);
    }

    @SaCheckPermission("biz:sales:list")
    @GetMapping("/by-product")
    public List<ProductSalesVO> byProduct(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return this.salesStatsService.byProduct(from, to);
    }

    @SaCheckPermission("biz:sales:list")
    @GetMapping("/monthly")
    public List<MonthlySalesVO> monthly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return this.salesStatsService.monthly(from, to);
    }

}
