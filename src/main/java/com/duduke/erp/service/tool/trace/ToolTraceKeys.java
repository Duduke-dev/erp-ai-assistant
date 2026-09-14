package com.duduke.erp.service.tool.trace;

/**
 * 一轮问答传给 Tool 的上下文字段名。
 * <p>
 * 单独成类是因为这是一份<b>契约</b>：编排层（提问链路）按这些 key 放值，
 * {@link LoggingToolCallback} 按同样的 key 取值写流水与恢复租户上下文。
 * 放在任一方的类里都会让另一方去依赖"对方的实现类"，而它们其实只需要共享字段名。
 * <p>
 * 全部字段<b>允许缺失</b>：Tool 也可能在没有完整链路上下文的场景被调用
 * （单元测试、手动刷新等）。缺了就退化为对应字段为 null，不阻断执行。
 */
public final class ToolTraceKeys {

    /** 一轮问答的链路 ID，同轮内多次 Tool 调用共用 */
    public static final String TRACE_ID = "traceId";

    public static final String CONVERSATION_ID = "conversationId";

    public static final String ENT_CODE = "entCode";

    public static final String USER_ID = "userId";

    /** auto / knowledge */
    public static final String MODE = "mode";

    public static final String MODEL = "model";

    private ToolTraceKeys() {
    }

}
