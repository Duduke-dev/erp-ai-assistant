package com.duduke.erp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.duduke.erp.entity.dto.SalesOrderQueryDTO;
import com.duduke.erp.entity.dto.SalesOrderSaveDTO;
import com.duduke.erp.entity.vo.SalesOrderVO;
import com.duduke.erp.service.SalesOrderService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 销售订单接口。
 * <p>
 * 更新为全量覆盖：明细一并提交，由服务端整体替换。
 */
@RestController
@RequestMapping("/api/biz/sales_orders")
@RequiredArgsConstructor
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    @SaCheckPermission("biz:sales:list")
    @GetMapping
    public IPage<SalesOrderVO> page(SalesOrderQueryDTO query) {
        return this.salesOrderService.page(query);
    }

    @SaCheckPermission("biz:sales:list")
    @GetMapping("/{id}")
    public SalesOrderVO get(@PathVariable Long id) {
        return this.salesOrderService.get(id);
    }

    @SaCheckPermission("biz:sales:save")
    @PostMapping
    public Long create(@RequestBody SalesOrderSaveDTO dto) {
        return this.salesOrderService.create(dto);
    }

    @SaCheckPermission("biz:sales:save")
    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody SalesOrderSaveDTO dto) {
        this.salesOrderService.update(id, dto);
    }

    @SaCheckPermission("biz:sales:save")
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        this.salesOrderService.remove(id);
    }

}
