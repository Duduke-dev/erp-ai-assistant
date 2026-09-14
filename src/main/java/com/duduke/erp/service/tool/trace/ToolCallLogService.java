package com.duduke.erp.service.tool.trace;

import com.duduke.erp.entity.po.ToolCallLog;
import com.duduke.erp.mapper.ToolCallLogMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Tool 调用流水落库。
 * <p>
 * <b>写库失败只记 warn，绝不抛出</b>：调用流水是旁路数据，
 * 它的价值在于事后排查，不能反过来影响正在进行的问答。
 * 这是本类唯一的硬性语义，测试里有专门的用例守着。
 * <p>
 * {@code ent_code} 不在这里赋值——由租户插件在 INSERT 时自动填充
 * （与全项目「业务代码不写 ent_code」的约定一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallLogService {

    /** error_summary 列是 varchar(1024)，超长会直接报错，先截断 */
    private static final int MAX_ERROR_SUMMARY = 1024;

    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 落一条调用流水。
     *
     * @param entry 待保存的流水；为 null 时静默忽略
     */
    public void save(ToolCallLog entry) {
        if (entry == null) {
            return;
        }
        try {
            truncateErrorSummary(entry);
            this.toolCallLogMapper.insert(entry);
        }
        catch (RuntimeException e) {
            // 旁路数据，失败不能冒泡
            log.warn("记录 Tool 调用流水失败：toolName={}，原因={}", entry.getToolName(), e.getMessage());
        }
    }

    private void truncateErrorSummary(ToolCallLog entry) {
        String summary = entry.getErrorSummary();
        if (summary != null && summary.length() > MAX_ERROR_SUMMARY) {
            entry.setErrorSummary(summary.substring(0, MAX_ERROR_SUMMARY));
        }
    }

}
