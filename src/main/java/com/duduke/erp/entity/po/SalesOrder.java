package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 销售订单。
 */
@Data
@TableName("sales_order")
public class SalesOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String orderNo;

    private Long customerId;

    /** 客户名称冗余存储，列表查询时避免回表 */
    private String customerName;

    private LocalDate orderDate;

    private LocalDate deliveryDate;

    /** 由明细行汇总而来 */
    private BigDecimal totalAmount;

    /** draft / confirmed / shipped / closed */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
