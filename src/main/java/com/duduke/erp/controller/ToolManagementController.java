package com.duduke.erp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.duduke.erp.entity.dto.LlmToolQueryDTO;
import com.duduke.erp.entity.dto.LlmToolSaveDTO;
import com.duduke.erp.entity.dto.ToolCallLogQueryDTO;
import com.duduke.erp.entity.vo.LlmToolVO;
import com.duduke.erp.entity.vo.ToolCallLogVO;
import com.duduke.erp.service.tool.ToolManagementService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态 Tool 管理接口。
 * <p>
 * 遵循手册「前后端规约」：POST 新建、PUT 更新、DELETE 删除、GET 查询；
 * 路径为资源名词、单词以下划线分隔、<b>不带 {@code /page}</b>（分页参数走 query）。
 * 查询用 {@code tool:llm:list}，写操作用 {@code tool:llm:save}（V8 已授予 admin）。
 * <p>
 * 三个资源挂在同一控制器下（{@code /api/tool} 这个 area）：
 * {@code llm_tools} 定义、{@code call_logs} 调用流水、{@code registry/refresh} 手动刷新。
 * 它们同属「Tool 运维」这一件事，拆成三个类只会让权限码与前缀各写三遍。
 */
@RestController
@RequestMapping("/api/tool")
@RequiredArgsConstructor
public class ToolManagementController {

    private final ToolManagementService toolManagementService;

    // ===== 动态 Tool 定义 =====

    @SaCheckPermission("tool:llm:list")
    @GetMapping("/llm_tools")
    public IPage<LlmToolVO> page(LlmToolQueryDTO query) {
        return this.toolManagementService.page(query);
    }

    @SaCheckPermission("tool:llm:list")
    @GetMapping("/llm_tools/{id}")
    public LlmToolVO get(@PathVariable Long id) {
        return this.toolManagementService.get(id);
    }

    @SaCheckPermission("tool:llm:save")
    @PostMapping("/llm_tools")
    public Long create(@RequestBody LlmToolSaveDTO dto) {
        return this.toolManagementService.create(dto);
    }

    @SaCheckPermission("tool:llm:save")
    @PutMapping("/llm_tools/{id}")
    public void update(@PathVariable Long id, @RequestBody LlmToolSaveDTO dto) {
        this.toolManagementService.update(id, dto);
    }

    @SaCheckPermission("tool:llm:save")
    @DeleteMapping("/llm_tools/{id}")
    public void remove(@PathVariable Long id) {
        this.toolManagementService.remove(id);
    }

    /**
     * 手动重建注册表快照。
     * <p>
     * 保存接口已自动刷新，这个入口只用于直接改库后的人工兜底。
     * 用 POST 是因为它是「触发一次动作」而非查询。
     *
     * @return 刷新后注册表中的 Tool 总数
     */
    @SaCheckPermission("tool:llm:save")
    @PostMapping("/registry/refresh")
    public int refreshRegistry() {
        return this.toolManagementService.refreshRegistry();
    }

    // ===== 调用流水 =====

    /**
     * 调用日志分页查询（自动限定在本租户内）。
     */
    @SaCheckPermission("tool:llm:list")
    @GetMapping("/call_logs")
    public IPage<ToolCallLogVO> pageLogs(ToolCallLogQueryDTO query) {
        return this.toolManagementService.pageLogs(query);
    }

}
