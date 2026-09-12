package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 客户。
 */
@Data
@TableName("customer")
public class Customer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String customerCode;

    private String customerName;

    private String contactPerson;

    private String contactPhone;

    private String region;

    /** 信用额度，下单时可用于超限校验 */
    private BigDecimal creditLimit;

    /** active / inactive */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
