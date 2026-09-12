package com.duduke.erp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.duduke.erp.entity.dto.CustomerQueryDTO;
import com.duduke.erp.entity.dto.CustomerSaveDTO;
import com.duduke.erp.entity.vo.CustomerVO;
import com.duduke.erp.service.CustomerService;

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
 * 客户接口。
 * <p>
 * 客户归入销售域，权限码沿用 {@code biz:sales:*}，与 V3 的域名保持一致。
 */
@RestController
@RequestMapping("/api/biz/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @SaCheckPermission("biz:sales:list")
    @GetMapping
    public IPage<CustomerVO> page(CustomerQueryDTO query) {
        return this.customerService.page(query);
    }

    @SaCheckPermission("biz:sales:list")
    @GetMapping("/{id}")
    public CustomerVO get(@PathVariable Long id) {
        return this.customerService.get(id);
    }

    @SaCheckPermission("biz:sales:save")
    @PostMapping
    public Long create(@RequestBody CustomerSaveDTO dto) {
        return this.customerService.create(dto);
    }

    @SaCheckPermission("biz:sales:save")
    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody CustomerSaveDTO dto) {
        this.customerService.update(id, dto);
    }

    @SaCheckPermission("biz:sales:save")
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        this.customerService.remove(id);
    }

}
