package com.duduke.erp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话编排参数。
 * <p>
 * 集中配置而非散落为常量：系统提示词的措辞、记忆窗口大小、单轮输出上限
 * 都会随模型与业务调整，放在同一处便于对比实验。
 * <p>
 * 本项目是**单模型**：不配置模型清单，chat 模型由
 * {@code spring.ai.openai.chat.options.model} 决定，这里只管编排行为。
 */
@Data
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

    /** 记忆窗口保留的消息条数（一轮问答 = user + assistant 两条） */
    private int memoryWindowSize = 20;

    /** 单轮回答的最大 token 数，为 0 表示不限制 */
    private int maxOutputTokens = 0;

    /** 会话标题的最大长度，由首条提问截断生成 */
    private int titleMaxLength = 40;

    /** 单条用户提问的最大长度，防止超长输入打爆上下文 */
    private int maxQuestionLength = 4000;

    /** 单会话允许的消息总数上限，超出则拒绝继续提问，防止无限增长 */
    private int maxMessagesPerConversation = 500;

    /**
     * 是否在流式错误事件（{@code StreamError.detail}）里附带原始异常摘要。
     * <p>
     * <b>默认关闭</b>：原始异常可能带表名、SQL 片段、内网地址与文件路径，
     * 生产环境把它下发给前端属于信息泄露。
     * <p>
     * 开发期打开的价值：SSE 场景下前端只拿到一条通用文案，
     * 没有 detail 就只能去服务端翻日志，两头对不上账。
     * 因此做成开关而非二选一——本地开、生产关。
     */
    private boolean exposeErrorDetail = false;

    // 注：曾试过加一个 forceToolForBusinessData 开关，用 tool_choice=required 强制模型调工具，
    // 以堵住「跳过查询直接编数字」。**实测失败，已移除**：DashScope 接受该取值不报错，
    // 但模型会陷入刀具调用循环（问库存却反复调 getTicketDetail，
    // 流水涨到 72 页、无任何回答文本输出）。留个默认关闭的开关只会误导后来者，
    // 因此连字段一起删掉。真正的解法是流式首轮缓冲 + 数据门控，见 AssistantService 里的说明。

    /**
     * 业务问答系统提示词（auto / data 模式）。
     * <p>
     * auto 模式同时挂了业务 Tool 与 RAG，所以第 5 条要求它**区分两类依据**：
     * 工具返回的是实时业务数据，检索到的是制度文档。不区分会让用户分不清
     * 「这个数字是查出来的」还是「这段规则是从手册里读到的」。
     */
    private String businessPrompt = """
            你是制造业 ERP 智能助手，服务于企业内部的业务人员。

            回答要求：
            1. 用简洁、专业的中文作答，直接给结论，不要复述问题；
            2. 涉及金额、数量、日期时保持与数据一致的精度，不要自行取整或估算；
            3. 数据不足时明确说明缺少什么信息，不要编造业务数据；
            4. **用 Markdown 组织回答**：要点用无序列表、多字段数据用表格、
               关键结论与数字用加粗；不使用 Markdown 一级标题（#），避免与页面标题冲突；
            5. 若上下文中附带了知识库资料，引用时在句末标注对应的方括号编号；
               业务数据与资料并用时，说清哪部分是查到的、哪部分出自文档；
            6. 涉及库存、订单、金额、数量、合格率等业务数据时，**必须先调用工具查询**；
               若本轮没有调用工具、或工具没有返回数据，**不要给出任何具体数字、明细或排行**，
               改为说明「本轮未查到业务数据，请确认查询条件」——凭印象编造的数字
               在格式上足以乱真，比明确的「查不到」危险得多。""";

    /** 知识问答系统提示词。引用编号规则由 RAG 上下文拼装阶段追加，此处不重复。 */
    private String knowledgePrompt = """
            你是企业知识库问答助手，只依据检索到的资料回答问题。

            回答要求：
            1. 严格基于提供的资料作答，不使用资料之外的知识；
            2. 引用资料时在句末标注对应的方括号编号；
            3. 资料不足以回答时直接说明「现有资料无法回答」，不要推测；
            4. **用 Markdown 组织回答**：要点用无序列表、多字段内容用表格、
               关键结论用加粗；不使用 Markdown 一级标题（#）。""";

}
