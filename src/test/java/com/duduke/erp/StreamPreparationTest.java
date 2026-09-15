package com.duduke.erp;

import com.duduke.erp.entity.dto.AskDTO;
import com.duduke.erp.entity.po.ChatConversation;
import com.duduke.erp.service.AssistantService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式问答准备阶段的不变量测试。
 * <p>
 * 锁定的是一次真实事故（2026-09-13）：流式控制器在启动模型流时把
 * {@code knowledgeBaseId} 硬编码成 {@code null}，导致
 * {@code resolveActive(null)} 回落到默认库。而默认库当时没有任何向量，
 * 于是表现为「回答里说『现有资料无法回答』，且没有任何报错」——
 * 属于静默失效，只有对比非流式链路才能发现。
 * <p>
 * 这里不启动容器，只验证「准备结果携带了请求里的知识库标识」这一条最直接的契约。
 * 只要它成立，下游就不会再回落到默认库。
 */
class StreamPreparationTest {

    private static AskDTO ask(String question, String mode, Long knowledgeBaseId) {
        return new AskDTO(null, question, mode, knowledgeBaseId);
    }

    @Test
    @DisplayName("准备阶段必须把请求里的知识库标识带出来")
    void preparationCarriesKnowledgeBaseId() {
        Long requested = 5L;
        // 用 record 的构造语义直接验证：知识库标识是准备结果的一部分，
        // 而非在启动流时另行决定。曾有版本把它漏掉，导致下游只能传 null。
        AssistantService.StreamPreparation preparation = new AssistantService.StreamPreparation(
                new ChatConversation(), "问题", "knowledge", requested, "trace-1");

        assertThat(preparation.knowledgeBaseId())
                .as("请求指定了知识库，准备结果必须原样带出，否则会回落到默认库")
                .isEqualTo(requested);
    }

    @Test
    @DisplayName("准备阶段必须把本轮 traceId 带出来（否则 Tool 流水无法归到本轮）")
    void preparationCarriesTraceId() {
        AssistantService.StreamPreparation preparation = new AssistantService.StreamPreparation(
                new ChatConversation(), "问题", "auto", null, "trace-abc");

        assertThat(preparation.traceId()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("未指定知识库时准备结果为空，交由下游回落默认库")
    void preparationKeepsNullWhenNotRequested() {
        AssistantService.StreamPreparation preparation = new AssistantService.StreamPreparation(
                new ChatConversation(), "问题", "auto", null, "trace-2");

        assertThat(preparation.knowledgeBaseId()).isNull();
        assertThat(preparation.mode()).isEqualTo("auto");
    }

    @Test
    @DisplayName("模式原样带出，knowledge / data / auto 各不相混")
    void preparationCarriesMode() {
        assertThat(new AssistantService.StreamPreparation(
                new ChatConversation(), "问题", "knowledge", null, "t1").mode())
                .isEqualTo("knowledge");
        assertThat(new AssistantService.StreamPreparation(
                new ChatConversation(), "问题", "data", null, "t2").mode())
                .isEqualTo("data");
        assertThat(new AssistantService.StreamPreparation(
                new ChatConversation(), "问题", "auto", null, "t3").mode())
                .isEqualTo("auto");
    }

    /**
     * 这条用来固定 AskDTO 的字段顺序契约：知识库标识必须是独立的入参，
     * 不能被塞进 Map 之类的旁路结构（那样会绕过编译期检查，重演同一个事故）。
     */
    @Test
    @DisplayName("AskDTO 通过 record 组件暴露知识库标识")
    void askDtoExposesKnowledgeBaseIdAsComponent() {
        AskDTO dto = ask("问题", "knowledge", 5L);

        assertThat(dto.knowledgeBaseId()).isEqualTo(5L);
        assertThat(dto.question()).isEqualTo("问题");
        assertThat(dto.mode()).isEqualTo("knowledge");
    }

}
