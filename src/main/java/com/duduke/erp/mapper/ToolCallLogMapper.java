package com.duduke.erp.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.ToolCallLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Tool 调用日志数据访问。
 * <p>
 * {@code tool_call_log} 有 ent_code 且<b>不在</b> {@code ignore-tables} 中，
 * 因此这里<b>刻意不加 {@code @InterceptorIgnore}</b>：租户条件由插件自动注入，
 * 手写反而多一处漏写的风险。这是 M3 定下的 Tool 层隔离方式——
 * 默认交给插件，只有实测插件改写有问题的语句才方法级关插件 + 显式 ent_code。
 */
public interface ToolCallLogMapper extends BaseMapper<ToolCallLog> {

    /**
     * 取某会话的全部 Tool 调用日志，按时间正序（同一轮的调用按发生顺序排列）。
     */
    @Select("""
            SELECT * FROM tool_call_log
            WHERE conversation_id = #{conversationId}
            ORDER BY id ASC
            """)
    List<ToolCallLog> selectByConversation(@Param("conversationId") String conversationId);

    /**
     * 取某轮问答（同一 traceId）的全部调用，用于按轮次复盘。
     */
    @Select("""
            SELECT * FROM tool_call_log
            WHERE trace_id = #{traceId}
            ORDER BY id ASC
            """)
    List<ToolCallLog> selectByTrace(@Param("traceId") String traceId);

}
