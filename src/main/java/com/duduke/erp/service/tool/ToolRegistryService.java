package com.duduke.erp.service.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.mapper.LlmToolMapper;
import com.duduke.erp.service.tool.dynamic.DatabaseToolCallbackFactory;
import com.duduke.erp.service.tool.dynamic.SqlToolValidator;
import com.duduke.erp.service.tool.trace.LoggingToolCallback;
import com.duduke.erp.service.tool.trace.ToolCallLogService;
import com.duduke.erp.service.tool.trace.ToolCallRecorder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Tool 注册表：把代码 Tool 与数据库动态 Tool 合并成一份不可变快照。
 *
 * <h3>三条设计要点</h3>
 * <ol>
 *   <li><b>不可变快照 + 原子发布</b>：{@code AtomicReference} 承载，
 *       请求线程无锁读取。刷新时构建全新快照再 {@code set}，
 *       进行中的问答仍用旧快照跑完——刷新不会打断任何人。</li>
 *   <li><b>每个 Tool 必须有权限声明</b>：缺失则拒绝注册并记 ERROR（fail-closed）。
 *       漏声明的后果是「模型用不了它」，不能是「谁都能用」。</li>
 *   <li><b>代码 Tool 优先于动态 Tool</b>：重名时保留代码 Tool、跳过动态那个。
 *       代码 Tool 是逐个审过的固定查询且有权限约束；动态 Tool 是管理端配的任意 SQL，
 *       让它顶掉代码 Tool 是错误的风险方向。</li>
 * </ol>
 *
 * <h3>为什么不需要「按权限缓存 ChatClient」</h3>
 * 参考实现为带 Tool 的调用建了 4 类缓存客户端，缓存键含工具版本号。
 * 本项目不需要：Spring AI 2.0 的 {@code ChatClientRequestSpec} 提供非弃用的
 * <b>按请求</b>传 {@code tools(Object...)} 入口（旧 {@code toolCallbacks(...)} 已弃用），
 * 所以只要一次构建 ChatClient，每轮把「过滤后的 Tool 列表」传进去即可——
 * 既没有缓存爆炸，也不存在失效问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    /** 全部业务 Tool。Spring 会把所有 {@link BusinessTool} 实现类注入进来 */
    private final List<BusinessTool> businessTools;

    private final LlmToolMapper llmToolMapper;

    private final DatabaseToolCallbackFactory databaseToolCallbackFactory;

    private final SqlToolValidator sqlToolValidator;

    private final ToolCallRecorder recorder;

    private final ToolCallLogService logService;

    private final ObjectMapper objectMapper;

    private final AtomicReference<ToolSnapshot> current = new AtomicReference<>(ToolSnapshot.empty());

    /**
     * 启动后刷新一次。
     * <p>
     * 失败只记 ERROR 不抛异常：Tool 不可用只是「助手能力变弱」，
     * 不该让整个应用起不来。此时快照为空，等于降级为无 Tool 模式。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        try {
            refresh();
        }
        catch (RuntimeException e) {
            log.error("启动时注册 Tool 失败，将不提供任何 Tool", e);
        }
    }

    /**
     * 重建快照并原子发布。
     * <p>
     * {@code synchronized}：并发刷新（管理端连续保存 + 启动事件）会互相覆盖，
     * 加锁保证「读-改-写版本号」是串行的。
     */
    public synchronized void refresh() {
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, String> permissions = new LinkedHashMap<>();
        Set<String> registered = new LinkedHashSet<>();

        int codeCount = registerCodeTools(callbacks, permissions, registered);
        int dynamicCount = registerDynamicTools(callbacks, permissions, registered);

        ToolSnapshot previous = this.current.get();
        ToolSnapshot next = new ToolSnapshot(previous.version() + 1, callbacks, permissions);
        this.current.set(next);
        log.info("Tool 注册表已刷新：version={}，共 {} 个（代码 {} + 动态 {}）",
                next.version(), next.size(), codeCount, dynamicCount);
    }

    /** 当前快照。请求线程直接读，无锁 */
    public ToolSnapshot snapshot() {
        return this.current.get();
    }

    private int registerCodeTools(List<ToolCallback> callbacks,
                                  Map<String, String> permissions,
                                  Set<String> registered) {
        ToolCallback[] codeCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(this.businessTools.toArray())
                .build()
                .getToolCallbacks();

        int count = 0;
        for (ToolCallback callback : codeCallbacks) {
            String name = callback.getToolDefinition().name();
            String permission = ToolPermissionCatalog.forToolName(name);
            if (permission == null) {
                // fail-closed：没有权限声明就不注册
                log.error("代码 Tool {} 未在 ToolPermissionCatalog 声明权限，已拒绝注册", name);
                continue;
            }
            if (!registered.add(name)) {
                log.warn("代码 Tool {} 重复定义，已跳过后出现的那个", name);
                continue;
            }
            callbacks.add(wrap(callback, LoggingToolCallback.SOURCE_CODE));
            permissions.put(name, permission);
            count++;
        }
        return count;
    }

    private int registerDynamicTools(List<ToolCallback> callbacks,
                                     Map<String, String> permissions,
                                     Set<String> registered) {
        List<LlmTool> definitions = this.llmToolMapper.selectActiveTools();
        int count = 0;
        for (LlmTool definition : definitions) {
            String name = definition.getToolName();
            if (ToolNames.isReserved(name)) {
                log.error("动态 Tool {} 使用了系统保留名，已跳过", name);
                continue;
            }
            try {
                // 存库后可能被直接改库绕过管理端校验，装载时再验一次
                this.sqlToolValidator.validateTool(definition);
            }
            catch (RuntimeException e) {
                log.error("动态 Tool {} 配置非法，已跳过：{}", name, e.getMessage());
                continue;
            }
            if (!registered.add(name)) {
                log.warn("动态 Tool {} 与已注册 Tool 重名，已跳过（保留代码 Tool）", name);
                continue;
            }
            callbacks.add(wrap(this.databaseToolCallbackFactory.create(definition),
                    LoggingToolCallback.SOURCE_DATABASE));
            permissions.put(name, ToolPermissionCatalog.DYNAMIC);
            count++;
        }
        return count;
    }

    /**
     * 给 Tool 套上调用流水与租户上下文保障。
     * <p>
     * 包装放在注册处，是因为<b>装配点只有这一处</b>——
     * 若交给调用方，任何一个漏包的入口都会得到「没有流水、且可能丢租户上下文」的 Tool，
     * 这类缺失不会报错，只会让日志莫名其妙地少一半。
     */
    private ToolCallback wrap(ToolCallback callback, String toolSource) {
        return new LoggingToolCallback(callback, toolSource, this.recorder, this.logService,
                this.objectMapper);
    }

}
