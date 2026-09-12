package com.duduke.erp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duduke.erp.entity.dto.InventoryQueryDTO;
import com.duduke.erp.entity.dto.InventorySaveDTO;
import com.duduke.erp.entity.po.Inventory;
import com.duduke.erp.entity.vo.InventoryVO;
import com.duduke.erp.mapper.InventoryMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 库存业务。
 * <p>
 * 租户隔离由 MyBatis-Plus 租户插件自动完成。
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryMapper inventoryMapper;

    /**
     * 分页查询。
     */
    public IPage<InventoryVO> page(InventoryQueryDTO query) {
        int pageNo = query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        int pageSize = query.pageSize() == null || query.pageSize() < 1
                ? 20 : Math.min(query.pageSize(), 200);

        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().trim() : null;
        LambdaQueryWrapper<Inventory> wrapper = Wrappers.<Inventory>lambdaQuery()
                .and(keyword != null, w -> w.like(Inventory::getProductName, keyword))
                .eq(StringUtils.hasText(query.warehouse()), Inventory::getWarehouse, query.warehouse())
                // 结存低于安全库存属于「字段与字段比较」，LambdaQueryWrapper 表达不了，用 SQL 片段
                .apply(Boolean.TRUE.equals(query.lowStock()), "quantity < safety_stock")
                .orderByDesc(Inventory::getId);

        IPage<Inventory> page = this.inventoryMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return page.convert(this::toVO);
    }

    /**
     * 按主键查询。
     */
    public InventoryVO get(Long id) {
        return toVO(requireExists(id));
    }

    /**
     * 新增。
     *
     * @return 新记录主键
     */
    public Long create(InventorySaveDTO dto) {
        if (dto.productId() == null) {
            throw new IllegalArgumentException("产品不能为空");
        }
        Inventory po = new Inventory();
        fill(po, dto);
        this.inventoryMapper.insert(po);
        return po.getId();
    }

    /**
     * 全量更新。
     */
    public void update(Long id, InventorySaveDTO dto) {
        requireExists(id);
        if (dto.productId() == null) {
            throw new IllegalArgumentException("产品不能为空");
        }
        Inventory po = new Inventory();
        po.setId(id);
        fill(po, dto);
        this.inventoryMapper.updateById(po);
    }

    private void fill(Inventory po, InventorySaveDTO dto) {
        po.setProductId(dto.productId());
        po.setProductName(dto.productName());
        po.setWarehouse(StringUtils.hasText(dto.warehouse()) ? dto.warehouse().trim() : "主仓");
        po.setLocation(dto.location());
        po.setBatchNo(dto.batchNo());
        po.setQuantity(dto.quantity() == null ? BigDecimal.ZERO : dto.quantity());
        po.setSafetyStock(dto.safetyStock() == null ? BigDecimal.ZERO : dto.safetyStock());
        // 该表无 created_at，只维护 updated_at
        po.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 删除。
     */
    public void remove(Long id) {
        requireExists(id);
        this.inventoryMapper.deleteById(id);
    }

    private Inventory requireExists(Long id) {
        Inventory po = this.inventoryMapper.selectById(id);
        if (po == null) {
            throw new IllegalArgumentException("库存记录不存在或不属于当前租户");
        }
        return po;
    }

    private InventoryVO toVO(Inventory po) {
        BigDecimal quantity = po.getQuantity() == null ? BigDecimal.ZERO : po.getQuantity();
        BigDecimal safety = po.getSafetyStock() == null ? BigDecimal.ZERO : po.getSafetyStock();
        return new InventoryVO(
                po.getId(),
                po.getProductId(),
                po.getProductName(),
                po.getWarehouse(),
                po.getLocation(),
                po.getBatchNo(),
                quantity,
                safety,
                quantity.compareTo(safety) < 0,
                po.getUpdatedAt());
    }

}
