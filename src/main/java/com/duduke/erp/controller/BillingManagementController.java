package com.duduke.erp.controller;

import java.util.List;

import com.duduke.erp.entity.dto.BillingAccountSaveDTO;
import com.duduke.erp.entity.dto.BillingPlanSaveDTO;
import com.duduke.erp.entity.dto.BillingPriceRuleSaveDTO;
import com.duduke.erp.entity.dto.BillingRechargeDTO;
import com.duduke.erp.entity.vo.BillingInvoiceVO;
import com.duduke.erp.entity.vo.BillingPlanVO;
import com.duduke.erp.entity.vo.BillingPriceRuleVO;
import com.duduke.erp.entity.vo.BillingTransactionVO;
import com.duduke.erp.service.BillingManagementService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费管理端接口：套餐与价格规则。
 * <p>
 * 遵循手册「前后端规约」：POST 新建 / PUT 更新 / DELETE 删除 / GET 查询，
 * 路径为资源名词、单词下划线分隔、不带 {@code /page}。
 * <p>
 * 读用 {@code billing:query}（admin 与 viewer 都有），
 * 写用 {@code billing:manage}（<b>仅 admin</b>，V12 授予）——
 * 这两张表是平台级配置，改一次影响所有租户。
 * <p>
 * 价格规则<b>只提供新增与删除，没有 PUT</b>：价格是带生效日期的历史记录，
 * 调价应当新增一条更晚生效的记录，而不是改写历史。
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingManagementController {

    private final BillingManagementService billingManagementService;

    // ===== 套餐 =====

    @SaCheckPermission("billing:query")
    @GetMapping("/plans")
    public List<BillingPlanVO> listPlans() {
        return this.billingManagementService.listPlans();
    }

    @SaCheckPermission("billing:query")
    @GetMapping("/plans/{id}")
    public BillingPlanVO getPlan(@PathVariable Long id) {
        return this.billingManagementService.getPlan(id);
    }

    @SaCheckPermission("billing:manage")
    @PostMapping("/plans")
    public Long createPlan(@RequestBody BillingPlanSaveDTO dto) {
        return this.billingManagementService.createPlan(dto);
    }

    @SaCheckPermission("billing:manage")
    @PutMapping("/plans/{id}")
    public void updatePlan(@PathVariable Long id, @RequestBody BillingPlanSaveDTO dto) {
        this.billingManagementService.updatePlan(id, dto);
    }

    @SaCheckPermission("billing:manage")
    @DeleteMapping("/plans/{id}")
    public void removePlan(@PathVariable Long id) {
        this.billingManagementService.removePlan(id);
    }

    // ===== 价格规则 =====

    @SaCheckPermission("billing:query")
    @GetMapping("/price_rules")
    public List<BillingPriceRuleVO> listPriceRules(
            @RequestParam(required = false) String modelName) {
        return this.billingManagementService.listPriceRules(modelName);
    }

    @SaCheckPermission("billing:manage")
    @PostMapping("/price_rules")
    public Long createPriceRule(@RequestBody BillingPriceRuleSaveDTO dto) {
        return this.billingManagementService.createPriceRule(dto);
    }

    @SaCheckPermission("billing:manage")
    @DeleteMapping("/price_rules/{id}")
    public void removePriceRule(@PathVariable Long id) {
        this.billingManagementService.removePriceRule(id);
    }

    // ===== 账户 =====
    //
    // 读接口（`billing:query`）也放在本控制器：它们查的流水与发票由同一个服务承载，
    // 拆到另一个控制器只会让同一条路径在两处注册，反而难追踪。

    @SaCheckPermission("billing:manage")
    @PostMapping("/accounts")
    public Long createAccount(@RequestBody BillingAccountSaveDTO dto) {
        return this.billingManagementService.createAccount(dto);
    }

    @SaCheckPermission("billing:manage")
    @PutMapping("/accounts/{id}")
    public void updateAccount(@PathVariable Long id, @RequestBody BillingAccountSaveDTO dto) {
        this.billingManagementService.updateAccount(id, dto);
    }

    /** 充值；金额必须为正，扣费不走这个接口 */
    @SaCheckPermission("billing:manage")
    @PostMapping("/accounts/{id}/recharges")
    public void recharge(@PathVariable Long id, @RequestBody BillingRechargeDTO dto) {
        this.billingManagementService.recharge(id, dto);
    }

    // ===== 交易流水与发票 =====

    @SaCheckPermission("billing:query")
    @GetMapping("/transactions")
    public List<BillingTransactionVO> listTransactions() {
        return this.billingManagementService.listTransactions();
    }

    @SaCheckPermission("billing:query")
    @GetMapping("/invoices")
    public List<BillingInvoiceVO> listInvoices() {
        return this.billingManagementService.listInvoices();
    }

    /**
     * 按账期开票。
     *
     * @param period 账期 {@code yyyy-MM}
     */
    @SaCheckPermission("billing:manage")
    @PostMapping("/invoices")
    public BillingInvoiceVO generateInvoice(@RequestParam String period) {
        return this.billingManagementService.generateInvoice(period);
    }

}
