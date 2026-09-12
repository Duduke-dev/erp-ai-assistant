package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 库存。
 * <p>
 * 按「产品 + 仓库 + 批次」记录当前结存；变动历史在 {@code stock_movement} 表，
 * 本表只保存汇总后的最新值。
 */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private Long productId;

    private String productName;

    /** 默认「主仓」 */
    private String warehouse;

    private String location;

    private String batchNo;

    private BigDecimal quantity;

    private BigDecimal safetyStock;

    /** 该表无 created_at，只跟踪最后更新时间 */
    private LocalDateTime updatedAt;

}
