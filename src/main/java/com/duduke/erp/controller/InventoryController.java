package com.duduke.erp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.duduke.erp.entity.dto.InventoryQueryDTO;
import com.duduke.erp.entity.dto.InventorySaveDTO;
import com.duduke.erp.entity.vo.InventoryVO;
import com.duduke.erp.service.InventoryService;

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
 * 库存接口。
 */
@RestController
@RequestMapping("/api/biz/inventories")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @SaCheckPermission("biz:inventory:list")
    @GetMapping
    public IPage<InventoryVO> page(InventoryQueryDTO query) {
        return this.inventoryService.page(query);
    }

    @SaCheckPermission("biz:inventory:list")
    @GetMapping("/{id}")
    public InventoryVO get(@PathVariable Long id) {
        return this.inventoryService.get(id);
    }

    @SaCheckPermission("biz:inventory:save")
    @PostMapping
    public Long create(@RequestBody InventorySaveDTO dto) {
        return this.inventoryService.create(dto);
    }

    @SaCheckPermission("biz:inventory:save")
    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody InventorySaveDTO dto) {
        this.inventoryService.update(id, dto);
    }

    @SaCheckPermission("biz:inventory:save")
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        this.inventoryService.remove(id);
    }

}
