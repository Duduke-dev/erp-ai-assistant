package com.duduke.erp.mapper;

import java.util.List;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.SysRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色数据访问。
 */
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询指定用户拥有的角色。
     * <p>
     * 联表场景下租户插件对 JOIN 表的条件注入行为不够可控，这里显式绕过插件，
     * 由 SQL 自己带上两张表的 ent_code 条件。
     *
     * @param userId  用户 ID
     * @param entCode 租户标识
     * @return 角色列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT r.* FROM sys_role r " +
        "JOIN sys_user_role ur ON ur.role_id = r.id " +
        "WHERE ur.user_id = #{userId} AND ur.ent_code = #{entCode} AND r.ent_code = #{entCode}")
    List<SysRole> selectRolesByUserId(@Param("userId") Long userId, @Param("entCode") String entCode);

}
