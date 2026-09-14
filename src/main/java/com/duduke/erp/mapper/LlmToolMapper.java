package com.duduke.erp.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.LlmTool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 动态 Tool 定义数据访问。
 * <p>
 * <b>本 Mapper 里不会出现 ent_code 条件，也不加 {@code @InterceptorIgnore}</b>：
 * {@code llm_tool} 是全局配置表，已在 {@code app.tenant.ignore-tables} 中，
 * 租户插件本来就会跳过它。再加 {@code @InterceptorIgnore} 属于冗余。
 * <p>
 * 租户隔离发生在<b>执行时</b>（{@code DatabaseToolExecutor} 往 SQL 注入 ent_code），
 * 不在这张表上。
 */
public interface LlmToolMapper extends BaseMapper<LlmTool> {

    /**
     * 取全部启用的 Tool 定义，供注册表构建快照。
     */
    @Select("""
            SELECT * FROM llm_tool
            WHERE status = 'active'
            ORDER BY id
            """)
    List<LlmTool> selectActiveTools();

    /**
     * 按名称取定义。<b>名称有唯一索引</b>，故最多一条；
     * 加 LIMIT 1 是为了在任何情况下都不抛 TooManyResultsException。
     */
    @Select("""
            SELECT * FROM llm_tool
            WHERE tool_name = #{toolName}
            LIMIT 1
            """)
    LlmTool selectByToolName(@Param("toolName") String toolName);

}
