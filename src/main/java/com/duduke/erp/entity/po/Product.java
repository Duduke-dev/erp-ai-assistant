package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 产品。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String productCode;

    private String productName;

    /** 规格型号 */
    private String spec;

    /** 计量单位，建表默认「个」 */
    private String unit;

    private String category;

    /** 安全库存，低于此值应在库存预警中体现 */
    private BigDecimal safetyStock;

    private BigDecimal unitPrice;

    /** active / inactive */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
