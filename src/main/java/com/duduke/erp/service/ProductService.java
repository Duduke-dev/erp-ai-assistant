package com.duduke.erp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duduke.erp.entity.dto.ProductQueryDTO;
import com.duduke.erp.entity.dto.ProductSaveDTO;
import com.duduke.erp.entity.po.Product;
import com.duduke.erp.entity.vo.ProductVO;
import com.duduke.erp.mapper.ProductMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 产品业务。
 * <p>
 * 租户隔离由 MyBatis-Plus 租户插件自动完成，这里不出现任何 ent_code 条件；
 * 只有需要跨租户或绕过上下文的场景才用 {@code @InterceptorIgnore} 显式声明。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;

    /**
     * 分页查询。
     */
    public IPage<ProductVO> page(ProductQueryDTO query) {
        int pageNo = query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        int pageSize = query.pageSize() == null || query.pageSize() < 1
                ? 20 : Math.min(query.pageSize(), 200);

        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().trim() : null;
        LambdaQueryWrapper<Product> wrapper = Wrappers.<Product>lambdaQuery()
                .and(keyword != null,
                        w -> w.like(Product::getProductCode, keyword)
                                .or()
                                .like(Product::getProductName, keyword))
                .eq(StringUtils.hasText(query.category()), Product::getCategory, query.category())
                .eq(StringUtils.hasText(query.status()), Product::getStatus, query.status())
                .orderByDesc(Product::getId);

        IPage<Product> page = this.productMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return page.convert(this::toVO);
    }

    /**
     * 按主键查询。
     */
    public ProductVO get(Long id) {
        return toVO(requireExists(id));
    }

    /**
     * 新增。
     *
     * @return 新产品主键
     */
    public Long create(ProductSaveDTO dto) {
        String code = requireCode(dto);
        String name = requireName(dto);
        checkCodeUnique(code, null);

        Product po = new Product();
        fill(po, dto, code, name);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        this.productMapper.insert(po);
        return po.getId();
    }

    /**
     * 全量更新。
     */
    public void update(Long id, ProductSaveDTO dto) {
        requireExists(id);
        String code = requireCode(dto);
        String name = requireName(dto);
        checkCodeUnique(code, id);

        Product po = new Product();
        po.setId(id);
        fill(po, dto, code, name);
        po.setUpdatedAt(LocalDateTime.now());
        this.productMapper.updateById(po);
    }

    private String requireCode(ProductSaveDTO dto) {
        String code = dto.productCode() == null ? "" : dto.productCode().trim();
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("产品编码不能为空");
        }
        return code;
    }

    private String requireName(ProductSaveDTO dto) {
        String name = dto.productName() == null ? "" : dto.productName().trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("产品名称不能为空");
        }
        return name;
    }

    private void fill(Product po, ProductSaveDTO dto, String code, String name) {
        po.setProductCode(code);
        po.setProductName(name);
        po.setSpec(dto.spec());
        po.setUnit(StringUtils.hasText(dto.unit()) ? dto.unit().trim() : "个");
        po.setCategory(dto.category());
        po.setSafetyStock(dto.safetyStock() == null ? BigDecimal.ZERO : dto.safetyStock());
        po.setUnitPrice(dto.unitPrice() == null ? BigDecimal.ZERO : dto.unitPrice());
        po.setStatus(StringUtils.hasText(dto.status()) ? dto.status().trim() : "active");
    }

    /**
     * 删除。
     */
    public void remove(Long id) {
        requireExists(id);
        this.productMapper.deleteById(id);
    }

    private Product requireExists(Long id) {
        Product po = this.productMapper.selectById(id);
        if (po == null) {
            throw new IllegalArgumentException("产品不存在或不属于当前租户");
        }
        return po;
    }

    private void checkCodeUnique(String productCode, Long id) {
        Long duplicated = this.productMapper.selectCount(Wrappers.<Product>lambdaQuery()
                .eq(Product::getProductCode, productCode)
                .ne(id != null, Product::getId, id));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("产品编码已存在：" + productCode);
        }
    }

    private ProductVO toVO(Product po) {
        return new ProductVO(
                po.getId(),
                po.getProductCode(),
                po.getProductName(),
                po.getSpec(),
                po.getUnit(),
                po.getCategory(),
                po.getSafetyStock(),
                po.getUnitPrice(),
                po.getStatus(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

}
