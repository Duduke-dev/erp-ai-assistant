package com.duduke.erp.entity.vo;

/**
 * SSE 流式事件的类型。
 * <p>
 * 用<b>类型化事件</b>而不是把 JSON 塞进 data 让前端自己猜：
 * 前端按 {@code event} 名分发，新增事件类型不会影响已有解析逻辑。
 * <p>
 * 事件顺序（成功路径）：
 * <pre>
 * meta  →  delta × n  →  citations（可选）  →  done
 * </pre>
 * 失败路径以 {@code error} 结尾；用户中断时只发已产生的 delta，不发终止事件——
 * 前端自己知道是它断的。
 */
public enum StreamEventType {

    /**
     * 会话元信息，连接建立后立刻下发。
     * <p>
     * 让前端在首帧正文到达前就拿到会话标识与模式，不必等 HTTP 响应头被解析。
     */
    META("meta"),

    /** 文本增量。data 为 {@link StreamDelta} */
    DELTA("delta"),

    /** 引用证据，流结束时一次性下发。data 为 {@link StreamCitations} */
    CITATIONS("citations"),

    /**
     * 数据缺失提示，在完成事件之前下发。
     * <p>
     * 触发条件：问题属于「要业务数据」的类型（见 {@code BusinessDataTurnGuard}），
     * 但本轮**没有拿到任何非空 Tool 结果**。
     * <p>
     * 为什么需要它：流式路径没有数据门控（见 {@code BusinessDataTurnGuard} 类注释
     * 「刻意不做流式门控」），模型可以选择不调工具、直接凭训练数据编。
     * 编出来的数字格式往往比真答案还规整，用户无从分辨。
     * 这里不做拦截（那要改 SSE 管线），只**把事实告诉用户**。
     */
    WARNING("warning"),

    /** 正常结束。data 为 {@link StreamDone} */
    DONE("done"),

    /** 生成失败。data 为 {@link StreamError} */
    ERROR("error");

    private final String eventName;

    StreamEventType(String eventName) {
        this.eventName = eventName;
    }

    /**
     * SSE 协议里的 event 名，前端据此分发。
     */
    public String eventName() {
        return this.eventName;
    }

}
