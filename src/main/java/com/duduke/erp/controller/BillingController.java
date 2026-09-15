package com.duduke.erp.controller;

import java.time.LocalDate;
import java.util.List;

import com.duduke.erp.entity.vo.BillingAccountVO;
import com.duduke.erp.entity.vo.TokenUsageVO;
import com.duduke.erp.service.BillingService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费查询接口（本租户）。
 * <p>
 * 遵循手册「前后端规约」：资源名词、单词下划线分隔、不带 {@code /page}。
 * 权限码 {@code billing:query}（V11 授予 admin 与 viewer）——
 * 查自己的用量不涉及越权，viewer 只读也应当能看。
 * <p>
 * 全部为只读 GET，因此本控制器不出现 {@code :save} 权限。
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    /**
     * 本租户计费账户；未开户时 {@code data} 为 null。
     */
    @SaCheckPermission("billing:query")
    @GetMapping("/account")
    public BillingAccountVO account() {
        return this.billingService.account();
    }

    /**
     * 日用量。
     *
     * @param from 起始日（含），ISO 格式 {@code yyyy-MM-dd}；可省略
     * @param to   截止日（含）；可省略
     */
    @SaCheckPermission("billing:query")
    @GetMapping("/usage/daily")
    public List<TokenUsageVO> dailyUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return this.billingService.dailyUsage(from, to);
    }

    /**
     * 月用量。
     *
     * @param from 起始账期 {@code yyyy-MM}；可省略
     * @param to   截止账期 {@code yyyy-MM}；可省略
     */
    @SaCheckPermission("billing:query")
    @GetMapping("/usage/monthly")
    public List<TokenUsageVO> monthlyUsage(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return this.billingService.monthlyUsage(from, to);
    }

}
