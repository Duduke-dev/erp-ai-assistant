package com.duduke.erp;

import java.nio.charset.StandardCharsets;

import com.duduke.erp.service.ObjectStorageService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RustFS 对象存储的真实联通验证。
 * <p>
 * <b>不是 mock</b>：直连本机 RustFS（9000）。这一点很重要——
 * 三个最容易配错的地方（{@code forcePathStyle}、region、endpoint 端口）
 * 用 mock 全都测不出来，它们只在真实签名与建连时才暴露。
 * 因此本用例要求 9000 端口可达；不可达时应视为环境问题而非代码问题。
 */
@SpringBootTest
class ObjectStorageServiceTest {

    @Autowired
    private ObjectStorageService storage;

    @Test
    @DisplayName("上传 → 下载：内容一致（验证路径风格与 SigV4 配置都对）")
    void putThenGetRoundTrip() {
        String key = this.storage.buildKey("DEMO", "手册.txt");
        byte[] content = "主仓库存共 180 件球阀".getBytes(StandardCharsets.UTF_8);

        try {
            String returned = this.storage.put(key, content, "text/plain");
            assertThat(returned).isEqualTo(key);

            byte[] downloaded = this.storage.get(key);
            assertThat(downloaded).isEqualTo(content);
        }
        finally {
            this.storage.remove(key);
        }
    }

    @Test
    @DisplayName("下载不存在的对象返回 null，而不是抛异常")
    void getMissingReturnsNull() {
        assertThat(this.storage.get("DEMO/no-such-object-" + System.nanoTime())).isNull();
    }

    @Test
    @DisplayName("删除是幂等的：删不存在的对象不报错")
    void removeIsIdempotent() {
        this.storage.remove("DEMO/never-existed-" + System.nanoTime());
    }

    @Test
    @DisplayName("预签名链接为路径风格，且带桶名与对象键")
    void presignIsPathStyle() {
        String key = this.storage.buildKey("DEMO", "扫描件.pdf");

        String url = this.storage.presignPut(key, "application/pdf");

        assertThat(url).startsWith("http");
        // 路径风格形如 http://host/bucket/key；虚拟主机风格会变成 http://bucket.host/key，
        // 而 RustFS 只认前者——这条断言正是为了保护 pathStyle 配置不被误删
        assertThat(url).contains("/erp-knowledge/");
        // 断言键的 ASCII 前缀即可：键里若含中文，URL 会做百分号编码（这是正确行为，
        // 直接断言原始键会失败——此处踩过一次）
        assertThat(url).contains(key.substring(0, key.lastIndexOf('/') + 1));
        // 预签名必须带上签名参数，否则链接等同于公开可写
        assertThat(url).contains("X-Amz-Signature");
    }

    @Test
    @DisplayName("对象键带租户前缀，且剔除路径穿越成分")
    void buildKeyIsTenantScopedAndSanitized() {
        String key = this.storage.buildKey("DEMO", "../../etc/passwd");

        assertThat(key).startsWith("DEMO/");
        assertThat(key)
                .as("文件名只取最后一段，避免以 ../../ 逃出预期目录")
                .endsWith("/passwd");
        assertThat(key).doesNotContain("..");
    }

}
