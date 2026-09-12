package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 租户。
 * <p>
 * 该表已加入 app.tenant.ignore-tables，不参与租户插件的条件注入；
 * 查询时必须自行带上 ent_code 条件。
 */
@Data
@TableName("tenant")
public class Tenant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String entName;

    /** active / disabled */
    private String status;

    private LocalDateTime createdAt;

}
