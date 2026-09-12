package com.duduke.erp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duduke.erp.entity.dto.SalesOrderItemSaveDTO;
import com.duduke.erp.entity.dto.SalesOrderQueryDTO;
import com.duduke.erp.entity.dto.SalesOrderSaveDTO;
import com.duduke.erp.entity.po.Customer;
import com.duduke.erp.entity.po.SalesOrder;
import com.duduke.erp.entity.po.SalesOrderItem;
import com.duduke.erp.entity.vo.SalesOrderItemVO;
import com.duduke.erp.entity.vo.SalesOrderVO;
import com.duduke.erp.mapper.CustomerMapper;
import com.duduke.erp.mapper.SalesOrderItemMapper;
import com.duduke.erp.mapper.SalesOrderMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 销售订单业务。
 * <p>
 * 订单与明细是一次写入的整体：金额由明细汇总而来，前端传来的金额一律不采信。
 * 更新采用「先删旧明细再插新明细」，比逐行 diff 更不容易出错。
 */
@Service
@RequiredArgsConstructor
public class SalesOrderService {

    private final SalesOrderMapper orderMapper;

    private final SalesOrderItemMapper itemMapper;

    private final CustomerMapper customerMapper;

    /**
     * 分页查询，明细一次性按订单号批量取出，避免逐条查库。
     */
    public IPage<SalesOrderVO> page(SalesOrderQueryDTO query) {
        int pageNo = query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        int pageSize = query.pageSize() == null || query.pageSize() < 1
                ? 20 : Math.min(query.pageSize(), 200);

        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().trim() : null;
        LambdaQueryWrapper<SalesOrder> wrapper = Wrappers.<SalesOrder>lambdaQuery()
                .and(keyword != null,
                        w -> w.like(SalesOrder::getOrderNo, keyword)
                                .or()
                                .like(SalesOrder::getCustomerName, keyword))
                .eq(query.customerId() != null, SalesOrder::getCustomerId, query.customerId())
                .eq(StringUtils.hasText(query.status()), SalesOrder::getStatus, query.status())
                .ge(query.dateFrom() != null, SalesOrder::getOrderDate, query.dateFrom())
                .le(query.dateTo() != null, SalesOrder::getOrderDate, query.dateTo())
                .orderByDesc(SalesOrder::getOrderDate)
                .orderByDesc(SalesOrder::getId);

        IPage<SalesOrder> page = this.orderMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        if (page.getRecords().isEmpty()) {
            return page.convert(o -> toVO(o, List.of()));
        }

        List<String> orderNos = page.getRecords().stream()
                .map(SalesOrder::getOrderNo)
                .toList();
        Map<String, List<SalesOrderItem>> grouped = this.itemMapper
                .selectList(Wrappers.<SalesOrderItem>lambdaQuery()
                        .in(SalesOrderItem::getOrderNo, orderNos))
                .stream()
                .collect(Collectors.groupingBy(SalesOrderItem::getOrderNo));

        return page.convert(o -> toVO(o, grouped.getOrDefault(o.getOrderNo(), List.of())));
    }

    /**
     * 按主键查询（含明细）。
     */
    public SalesOrderVO get(Long id) {
        SalesOrder order = requireExists(id);
        List<SalesOrderItem> items = this.itemMapper
                .selectList(Wrappers.<SalesOrderItem>lambdaQuery()
                        .eq(SalesOrderItem::getOrderNo, order.getOrderNo()));
        return toVO(order, items);
    }

    /**
     * 新增订单及其明细。
     *
     * @return 新订单主键
     */
    @Transactional
    public Long create(SalesOrderSaveDTO dto) {
        String orderNo = requireOrderNo(dto);
        Customer customer = requireCustomer(dto);
        requireItems(dto);
        checkOrderNoUnique(orderNo, null);

        SalesOrder order = new SalesOrder();
        fill(order, dto, orderNo, customer);
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        this.orderMapper.insert(order);
        insertItems(orderNo, dto.items());
        return order.getId();
    }

    /**
     * 全量更新订单及其明细。
     * <p>
     * 明细采用整体替换：先删旧行再插新行，比逐行 diff 更不易出错。
     */
    @Transactional
    public void update(Long id, SalesOrderSaveDTO dto) {
        SalesOrder existing = requireExists(id);
        String orderNo = requireOrderNo(dto);
        Customer customer = requireCustomer(dto);
        requireItems(dto);
        checkOrderNoUnique(orderNo, id);

        SalesOrder order = new SalesOrder();
        order.setId(id);
        fill(order, dto, orderNo, customer);
        order.setUpdatedAt(LocalDateTime.now());
        this.orderMapper.updateById(order);

        this.itemMapper.delete(Wrappers.<SalesOrderItem>lambdaQuery()
                .eq(SalesOrderItem::getOrderNo, existing.getOrderNo()));
        insertItems(orderNo, dto.items());
    }

    private String requireOrderNo(SalesOrderSaveDTO dto) {
        String orderNo = dto.orderNo() == null ? "" : dto.orderNo().trim();
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        return orderNo;
    }

    private Customer requireCustomer(SalesOrderSaveDTO dto) {
        if (dto.customerId() == null) {
            throw new IllegalArgumentException("客户不能为空");
        }
        Customer customer = this.customerMapper.selectById(dto.customerId());
        if (customer == null) {
            throw new IllegalArgumentException("客户不存在或不属于当前租户");
        }
        return customer;
    }

    private void requireItems(SalesOrderSaveDTO dto) {
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("订单至少需要一个明细行");
        }
    }

    private void fill(SalesOrder order, SalesOrderSaveDTO dto, String orderNo, Customer customer) {
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrderItemSaveDTO item : dto.items()) {
            total = total.add(amountOf(item));
        }
        order.setOrderNo(orderNo);
        order.setCustomerId(dto.customerId());
        order.setCustomerName(customer.getCustomerName());
        order.setOrderDate(dto.orderDate());
        order.setDeliveryDate(dto.deliveryDate());
        order.setTotalAmount(total);
        order.setStatus(StringUtils.hasText(dto.status()) ? dto.status().trim() : "draft");
        order.setRemark(dto.remark());
    }

    private void insertItems(String orderNo, List<SalesOrderItemSaveDTO> items) {
        for (SalesOrderItemSaveDTO item : items) {
            SalesOrderItem po = new SalesOrderItem();
            po.setOrderNo(orderNo);
            po.setProductId(item.productId());
            po.setProductName(item.productName());
            po.setQuantity(item.quantity() == null ? BigDecimal.ZERO : item.quantity());
            po.setUnitPrice(item.unitPrice() == null ? BigDecimal.ZERO : item.unitPrice());
            po.setAmount(amountOf(item));
            po.setCreatedAt(LocalDateTime.now());
            this.itemMapper.insert(po);
        }
    }

    /**
     * 删除订单及其明细。
     */
    @Transactional
    public void remove(Long id) {
        SalesOrder order = requireExists(id);
        this.itemMapper.delete(Wrappers.<SalesOrderItem>lambdaQuery()
                .eq(SalesOrderItem::getOrderNo, order.getOrderNo()));
        this.orderMapper.deleteById(id);
    }

    private BigDecimal amountOf(SalesOrderItemSaveDTO item) {
        BigDecimal quantity = item.quantity() == null ? BigDecimal.ZERO : item.quantity();
        BigDecimal price = item.unitPrice() == null ? BigDecimal.ZERO : item.unitPrice();
        return quantity.multiply(price);
    }

    private SalesOrder requireExists(Long id) {
        SalesOrder order = this.orderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在或不属于当前租户");
        }
        return order;
    }

    private void checkOrderNoUnique(String orderNo, Long id) {
        Long duplicated = this.orderMapper.selectCount(Wrappers.<SalesOrder>lambdaQuery()
                .eq(SalesOrder::getOrderNo, orderNo)
                .ne(id != null, SalesOrder::getId, id));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("订单号已存在：" + orderNo);
        }
    }

    private SalesOrderVO toVO(SalesOrder order, List<SalesOrderItem> items) {
        List<SalesOrderItemVO> itemVOs = items.stream()
                .map(i -> new SalesOrderItemVO(
                        i.getId(),
                        i.getProductId(),
                        i.getProductName(),
                        i.getQuantity(),
                        i.getUnitPrice(),
                        i.getAmount(),
                        i.getCreatedAt()))
                .toList();
        return new SalesOrderVO(
                order.getId(),
                order.getOrderNo(),
                order.getCustomerId(),
                order.getCustomerName(),
                order.getOrderDate(),
                order.getDeliveryDate(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getRemark(),
                itemVOs,
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

}
