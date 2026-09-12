package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 系统用户。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String username;

    /** BCrypt 散列，不以明文存储 */
    private String passwordHash;

    private String realName;

    private String phone;

    /** active / disabled */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
