package com.duduke.erp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.duduke.erp.entity.dto.ProductQueryDTO;
import com.duduke.erp.entity.dto.ProductSaveDTO;
import com.duduke.erp.entity.vo.ProductVO;
import com.duduke.erp.service.ProductService;

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
 * 产品接口。
 * <p>
 * 遵循手册「前后端规约」：路径为资源名词复数、单词以下划线分隔；
 * POST 新建、PUT 更新、DELETE 删除、GET 查询。
 * 查询用 {@code biz:product:list}，写操作用 {@code biz:product:save}。
 */
@RestController
@RequestMapping("/api/biz/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @SaCheckPermission("biz:product:list")
    @GetMapping
    public IPage<ProductVO> page(ProductQueryDTO query) {
        return this.productService.page(query);
    }

    @SaCheckPermission("biz:product:list")
    @GetMapping("/{id}")
    public ProductVO get(@PathVariable Long id) {
        return this.productService.get(id);
    }

    @SaCheckPermission("biz:product:save")
    @PostMapping
    public Long create(@RequestBody ProductSaveDTO dto) {
        return this.productService.create(dto);
    }

    @SaCheckPermission("biz:product:save")
    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody ProductSaveDTO dto) {
        this.productService.update(id, dto);
    }

    @SaCheckPermission("biz:product:save")
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        this.productService.remove(id);
    }

}
