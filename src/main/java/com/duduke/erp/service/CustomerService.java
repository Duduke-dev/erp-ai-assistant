package com.duduke.erp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duduke.erp.entity.dto.CustomerQueryDTO;
import com.duduke.erp.entity.dto.CustomerSaveDTO;
import com.duduke.erp.entity.po.Customer;
import com.duduke.erp.entity.vo.CustomerVO;
import com.duduke.erp.mapper.CustomerMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 客户业务。
 * <p>
 * 租户隔离由 MyBatis-Plus 租户插件自动完成，这里不出现 ent_code 条件。
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerMapper customerMapper;

    /**
     * 分页查询。
     */
    public IPage<CustomerVO> page(CustomerQueryDTO query) {
        int pageNo = query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        int pageSize = query.pageSize() == null || query.pageSize() < 1
                ? 20 : Math.min(query.pageSize(), 200);

        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().trim() : null;
        LambdaQueryWrapper<Customer> wrapper = Wrappers.<Customer>lambdaQuery()
                .and(keyword != null,
                        w -> w.like(Customer::getCustomerCode, keyword)
                                .or()
                                .like(Customer::getCustomerName, keyword))
                .eq(StringUtils.hasText(query.region()), Customer::getRegion, query.region())
                .eq(StringUtils.hasText(query.status()), Customer::getStatus, query.status())
                .orderByDesc(Customer::getId);

        IPage<Customer> page = this.customerMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return page.convert(this::toVO);
    }

    /**
     * 按主键查询。
     */
    public CustomerVO get(Long id) {
        return toVO(requireExists(id));
    }

    /**
     * 新增。
     *
     * @return 新客户主键
     */
    public Long create(CustomerSaveDTO dto) {
        String code = requireCode(dto);
        String name = requireName(dto);
        checkCodeUnique(code, null);

        Customer po = new Customer();
        fill(po, dto, code, name);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        this.customerMapper.insert(po);
        return po.getId();
    }

    /**
     * 全量更新。
     */
    public void update(Long id, CustomerSaveDTO dto) {
        requireExists(id);
        String code = requireCode(dto);
        String name = requireName(dto);
        checkCodeUnique(code, id);

        Customer po = new Customer();
        po.setId(id);
        fill(po, dto, code, name);
        po.setUpdatedAt(LocalDateTime.now());
        this.customerMapper.updateById(po);
    }

    private String requireCode(CustomerSaveDTO dto) {
        String code = dto.customerCode() == null ? "" : dto.customerCode().trim();
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("客户编码不能为空");
        }
        return code;
    }

    private String requireName(CustomerSaveDTO dto) {
        String name = dto.customerName() == null ? "" : dto.customerName().trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("客户名称不能为空");
        }
        return name;
    }

    private void fill(Customer po, CustomerSaveDTO dto, String code, String name) {
        po.setCustomerCode(code);
        po.setCustomerName(name);
        po.setContactPerson(dto.contactPerson());
        po.setContactPhone(dto.contactPhone());
        po.setRegion(dto.region());
        po.setCreditLimit(dto.creditLimit() == null ? BigDecimal.ZERO : dto.creditLimit());
        po.setStatus(StringUtils.hasText(dto.status()) ? dto.status().trim() : "active");
    }

    /**
     * 删除。
     */
    public void remove(Long id) {
        requireExists(id);
        this.customerMapper.deleteById(id);
    }

    private Customer requireExists(Long id) {
        Customer po = this.customerMapper.selectById(id);
        if (po == null) {
            throw new IllegalArgumentException("客户不存在或不属于当前租户");
        }
        return po;
    }

    private void checkCodeUnique(String customerCode, Long id) {
        Long duplicated = this.customerMapper.selectCount(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getCustomerCode, customerCode)
                .ne(id != null, Customer::getId, id));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("客户编码已存在：" + customerCode);
        }
    }

    private CustomerVO toVO(Customer po) {
        return new CustomerVO(
                po.getId(),
                po.getCustomerCode(),
                po.getCustomerName(),
                po.getContactPerson(),
                po.getContactPhone(),
                po.getRegion(),
                po.getCreditLimit(),
                po.getStatus(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

}
