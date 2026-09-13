package com.duduke.erp;

import java.util.List;

import com.duduke.erp.entity.vo.RagCitation;
import com.duduke.erp.service.RagCitationService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用编号提取与校验的单元测试。
 * <p>
 * 这里覆盖的是实机跑出来的模型偏差，而不是理想路径：
 * 全角括号、编号越界、代码块里的假引用、超长编号。
 * 每一条都对应过线上真实现象，删测试前请先确认现象不会再出现。
 */
class RagCitationServiceTest {

    private final RagCitationService service = new RagCitationService(new ObjectMapper());

    private static Document doc(String source, String text) {
        return Document.builder()
                .id(source + "-" + text.hashCode())
                .text(text)
                .metadata("source", source)
                .build();
    }

    private static List<Document> twoDocs() {
        return List.of(doc("a.txt", "物料主数据的新增路径"), doc("b.txt", "库存低于安全库存会预警"));
    }

    @Test
    @DisplayName("半角编号可被提取")
    void extractsHalfWidth() {
        assertThat(this.service.extractCitationNumbers("见 [1] 和 [2]")).containsExactly(1, 2);
    }

    @Test
    @DisplayName("全角编号同样可被提取：中文语境下模型常输出【n】")
    void extractsFullWidth() {
        assertThat(this.service.extractCitationNumbers("见【1】与【2】")).containsExactly(1, 2);
    }

    @Test
    @DisplayName("代码块与行内代码里的编号不算引用")
    void ignoresCodeBlocks() {
        String answer = """
                正常引用 [1]
                ```json
                {"index": [2]}
                ```
                行内 `arr[3]` 也不算
                """;
        assertThat(this.service.extractCitationNumbers(answer)).containsExactly(1);
    }

    @Test
    @DisplayName("转义的方括号不算引用")
    void ignoresEscaped() {
        assertThat(this.service.extractCitationNumbers("字面量 \\[1] 不是引用")).isEmpty();
    }

    @Test
    @DisplayName("编号在证据范围内的引用按序返回")
    void validatesInRange() {
        List<RagCitation> citations = this.service.validate("先说 [1]，再说 [2]", twoDocs());
        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).index()).isEqualTo(1);
        assertThat(citations.get(0).source()).isEqualTo("a.txt");
        assertThat(citations.get(1).index()).isEqualTo(2);
    }

    @Test
    @DisplayName("温和越界按证据条数回绕：多轮下模型把编号当全局累计")
    void wrapsMildOverflow() {
        // 本轮只有 2 条证据，模型写 [3]，应归位到 [1]
        List<RagCitation> citations = this.service.validate("库存预警见 [3]", twoDocs());
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).index()).isEqualTo(1);
        assertThat(citations.get(0).source()).isEqualTo("a.txt");
    }

    @Test
    @DisplayName("远离证据范围的编号视为编造并丢弃")
    void dropsWildOverflow() {
        // 2 条证据的容忍上限是 4，[99] 超出，不得回绕
        assertThat(this.service.validate("凭空引用 [99]", twoDocs())).isEmpty();
    }

    @Test
    @DisplayName("单条证据时编号不得无条件归位到 [1]")
    void doesNotBlanketFirstWithSingleEvidence() {
        List<Document> one = List.of(doc("a.txt", "唯一证据"));
        assertThat(this.service.validate("引用 [1]", one)).hasSize(1);
        assertThat(this.service.validate("引用 [2]", one)).hasSize(1);
        // 2 条倍数的容忍上限为 2，[5] 已越界
        assertThat(this.service.validate("引用 [5]", one)).isEmpty();
    }

    @Test
    @DisplayName("重复编号只保留一次")
    void deduplicates() {
        List<RagCitation> citations = this.service.validate("[1] 与 [1] 与 [2]", twoDocs());
        assertThat(citations).hasSize(2);
        assertThat(citations).extracting(RagCitation::index).containsExactly(1, 2);
    }

    @Test
    @DisplayName("无证据或空回答时返回空引用")
    void emptyInputs() {
        assertThat(this.service.validate("有 [1] 但没证据", List.of())).isEmpty();
        assertThat(this.service.validate(null, twoDocs())).isEmpty();
    }

    @Test
    @DisplayName("引用 JSON 编解码可往返")
    void encodesAndDecodes() {
        List<RagCitation> original = this.service.validate("见 [1]", twoDocs());
        String json = this.service.encode(original);
        assertThat(this.service.decode(json)).isEqualTo(original);
    }

    @Test
    @DisplayName("损坏的引用 JSON 降级为空而非抛异常")
    void decodeToleratesGarbage() {
        assertThat(this.service.decode("{不是数组")).isEmpty();
        assertThat(this.service.decode(null)).isEmpty();
        assertThat(this.service.decode("  ")).isEmpty();
    }

}
