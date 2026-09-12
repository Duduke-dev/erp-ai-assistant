package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 系统角色。
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String roleCode;

    private String roleName;

    /** 逗号分隔的权限码，由 StpInterfaceImpl 读取后交给 Sa-Token 校验 */
    private String permissions;

    private LocalDateTime createdAt;

}
