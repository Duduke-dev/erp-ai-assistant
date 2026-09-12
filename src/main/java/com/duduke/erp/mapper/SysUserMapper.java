package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统用户数据访问。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 登录查询。
     * <p>
     * 登录发生在建立租户上下文之前，租户插件取不到 ent_code 会直接抛异常。
     * 这里用 {@code @InterceptorIgnore} 绕过插件，改由 SQL 显式带 ent_code 条件，
     * 保证既隔离租户又不依赖线程上下文。
     *
     * @param entCode  租户标识
     * @param username 用户名
     * @return 用户实体，不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE ent_code = #{entCode} AND username = #{username} LIMIT 1")
    SysUser selectForLogin(@Param("entCode") String entCode, @Param("username") String username);

}
