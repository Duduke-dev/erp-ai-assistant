package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 销售订单明细。
 * <p>
 * 通过 {@code order_no} 与 {@link SalesOrder} 关联，不存外键 id：
 * 订单号在租户内唯一，作为业务主键更贴近实际单据流转。
 */
@Data
@TableName("sales_order_item")
public class SalesOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String orderNo;

    private Long productId;

    private String productName;

    private BigDecimal quantity;

    private BigDecimal unitPrice;

    /** quantity * unitPrice，由 Service 落库前计算 */
    private BigDecimal amount;

    private LocalDateTime createdAt;

}
