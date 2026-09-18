package com.duduke.erp.service.tool;

import java.util.Set;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.LlmToolQueryDTO;
import com.duduke.erp.entity.dto.LlmToolSaveDTO;
import com.duduke.erp.entity.dto.ToolCallLogQueryDTO;
import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.entity.po.ToolCallLog;
import com.duduke.erp.entity.vo.LlmToolVO;
import com.duduke.erp.entity.vo.ToolCallLogVO;
import com.duduke.erp.mapper.LlmToolMapper;
import com.duduke.erp.mapper.ToolCallLogMapper;
import com.duduke.erp.service.tool.dynamic.SqlToolValidator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 动态 Tool 管理端。
 *
 * <h3>三条容易做错的地方</h3>
 * <ol>
 *   <li><b>名称不得与代码 Tool 冲突</b>：{@code SqlToolValidator} 只挡系统保留名
 *       （当前为空集），<b>不覆盖 39 个代码 Tool</b>。
 *       若放任重名，注册表会「保留代码 Tool、跳过动态那个」，
 *       结果是管理端显示保存成功、模型却永远看不到它——<b>不报错的哑弹</b>。
 *       所以这里在保存前显式比对 {@code ToolPermissionCatalog} 已声明的名称。</li>
 *   <li><b>快照在事务提交后刷新</b>：若在事务内刷新，一旦随后回滚，
 *       注册表就加载了从未真正提交的数据。用 {@code afterCommit} 回调规避。</li>
 *   <li><b>调用日志不写租户条件</b>：{@code tool_call_log} 有 ent_code 且不在
 *       ignore-tables 中，MP 插件会自动注入；手写反而多一处漏写的风险。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolManagementService {

    private static final String STATUS_ACTIVE = "active";

    private static final String STATUS_INACTIVE = "inactive";

    private static final Set<String> ALLOWED_STATUS = Set.of(STATUS_ACTIVE, STATUS_INACTIVE);

    private final LlmToolMapper llmToolMapper;

    private final ToolCallLogMapper toolCallLogMapper;

    private final SqlToolValidator sqlToolValidator;

    private final ToolRegistryService toolRegistryService;

    /**
     * 分页查询 Tool 定义。
     * <p>
     * {@code llm_tool} 是全局配置表（无 ent_code），故无需租户条件。
     */
    public IPage<LlmToolVO> page(LlmToolQueryDTO query) {
        String keyword = StringUtils.hasText(query.keyword()) ? query.keyword().trim() : null;
        var wrapper = Wrappers.<LlmTool>lambdaQuery()
                .and(keyword != null,
                        w -> w.like(LlmTool::getToolName, keyword)
                                .or()
                                .like(LlmTool::getToolDesc, keyword))
                .eq(StringUtils.hasText(query.status()), LlmTool::getStatus, query.status())
                .orderByAsc(LlmTool::getId);

        IPage<LlmTool> page = this.llmToolMapper.selectPage(pageOf(query.pageNo(),
                query.pageSize()), wrapper);
        return page.convert(this::toVO);
    }

    /**
     * 按主键查询。
     */
    public LlmToolVO get(Long id) {
        return toVO(requireExists(id));
    }

    /**
     * 新增。
     *
     * @return 新 Tool 主键
     */
    @Transactional
    public Long create(LlmToolSaveDTO dto) {
        LlmTool tool = toEntity(dto, null);
        validateForSave(tool, null);
        this.llmToolMapper.insert(tool);
        refreshAfterCommit();
        return tool.getId();
    }

    /**
     * 全量更新。
     */
    @Transactional
    public void update(Long id, LlmToolSaveDTO dto) {
        requireExists(id);
        LlmTool tool = toEntity(dto, id);
        validateForSave(tool, id);
        this.llmToolMapper.updateById(tool);
        refreshAfterCommit();
    }

    /**
     * 删除。
     */
    @Transactional
    public void remove(Long id) {
        requireExists(id);
        this.llmToolMapper.deleteById(id);
        refreshAfterCommit();
    }

    /**
     * 手动重建注册表快照。
     * <p>
     * 正常保存会自动刷新，这个入口用于：直接改库、或怀疑快照与库不一致时的人工兜底。
     *
     * @return 刷新后注册表中的 Tool 总数
     */
    public int refreshRegistry() {
        this.toolRegistryService.refresh();
        return this.toolRegistryService.snapshot().size();
    }

    /**
     * 分页查询调用日志（自动限定在本租户内）。
     */
    public IPage<ToolCallLogVO> pageLogs(ToolCallLogQueryDTO query) {
        var wrapper = Wrappers.<ToolCallLog>lambdaQuery()
                .eq(StringUtils.hasText(query.toolName()), ToolCallLog::getToolName, query.toolName())
                .eq(StringUtils.hasText(query.status()), ToolCallLog::getStatus, query.status())
                .eq(StringUtils.hasText(query.toolSource()), ToolCallLog::getToolSource,
                        query.toolSource())
                .eq(StringUtils.hasText(query.traceId()), ToolCallLog::getTraceId, query.traceId())
                .orderByDesc(ToolCallLog::getId);

        IPage<ToolCallLog> page = this.toolCallLogMapper.selectPage(
                pageOf(query.pageNo(), query.pageSize()), wrapper);
        return page.convert(this::toLogVO);
    }

    // ===== 校验与转换 =====

    /**
     * 保存前校验：复用 {@code SqlToolValidator}（与「装载时」同一套规则），
     * 再补上它管不到的两件事——与代码 Tool 重名、与同表其他记录重名。
     *
     * @param selfId 更新时的自身主键（排除自己）；新增时为 null
     */
    private void validateForSave(LlmTool tool, Long selfId) {
        try {
            this.sqlToolValidator.validateTool(tool);
        }
        catch (IllegalArgumentException e) {
            // 校验器的异常是给用户看的，直接透传为业务异常（400 而非 500）
            throw new BusinessException(e.getMessage());
        }

        String toolName = tool.getToolName();
        if (ToolPermissionCatalog.declaredToolNames().contains(toolName)) {
            throw new BusinessException(
                    "Tool 名称与内置 Tool 冲突：" + toolName + "（内置 Tool 优先，动态 Tool 不会被注册）");
        }

        LlmTool sameName = this.llmToolMapper.selectByToolName(toolName);
        if (sameName != null && !sameName.getId().equals(selfId)) {
            throw new BusinessException("Tool 名称已存在：" + toolName);
        }
    }

    /**
     * 把 DTO 转成实体，顺便做两处归一化。
     * <p>
     * <b>状态为空按 active 处理</b>：注册表只加载 active 的记录，
     * 若允许空值存进去，管理端看着有一条、模型却一个都调不到——又一颗哑弹。
     */
    private LlmTool toEntity(LlmToolSaveDTO dto, Long id) {
        if (dto == null) {
            throw new BusinessException("请求体不能为空");
        }
        LlmTool tool = new LlmTool();
        tool.setId(id);
        tool.setToolName(trimToNull(dto.toolName()));
        tool.setToolDesc(trimToNull(dto.toolDesc()));
        tool.setInputSchema(trimToNull(dto.inputSchema()));
        tool.setSqlTemplate(trimToNull(dto.sqlTemplate()));
        tool.setTableAlias(trimToNull(dto.tableAlias()));
        tool.setResultLimit(dto.resultLimit());
        tool.setRemark(trimToNull(dto.remark()));

        String status = trimToNull(dto.status());
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        else if (!ALLOWED_STATUS.contains(status)) {
            throw new BusinessException("状态只能是 active 或 inactive，实际为：" + status);
        }
        tool.setStatus(status);
        return tool;
    }

    private LlmToolVO toVO(LlmTool tool) {
        return new LlmToolVO(tool.getId(), tool.getToolName(), tool.getToolDesc(),
                tool.getInputSchema(), tool.getSqlTemplate(), tool.getTableAlias(),
                tool.getResultLimit(), tool.getStatus(), tool.getRemark(),
                tool.getCreatedAt(), tool.getUpdatedAt());
    }

    private ToolCallLogVO toLogVO(ToolCallLog log) {
        return new ToolCallLogVO(log.getId(), log.getConversationId(), log.getTraceId(),
                log.getToolName(), log.getToolSource(), log.getModelName(), log.getMode(),
                log.getArguments(), log.getStatus(), log.getElapsedMs(), log.getResultCount(),
                log.getErrorSummary(), log.getCreatedAt());
    }

    private LlmTool requireExists(Long id) {
        LlmTool tool = this.llmToolMapper.selectById(id);
        if (tool == null) {
            throw new BusinessException("Tool 不存在：" + id);
        }
        return tool;
    }

    /**
     * 提交后再刷新快照。
     * <p>
     * 没有活动事务时（如单元测试直调）直接刷新，否则注册表会一直停留在旧快照。
     */
    private void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            this.toolRegistryService.refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ToolManagementService.this.toolRegistryService.refresh();
            }
        });
    }

    private static <T> Page<T> pageOf(Integer pageNo, Integer pageSize) {
        int no = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
        return new Page<>(no, size);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

}
