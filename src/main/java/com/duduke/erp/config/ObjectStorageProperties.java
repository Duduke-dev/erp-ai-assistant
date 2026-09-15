package com.duduke.erp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储（RustFS）配置。
 *
 * <h3>三个必须显式指定的东西</h3>
 * <ol>
 *   <li><b>{@code path-style=true}</b>：S3 默认走虚拟主机风格 URL
 *       （{@code http://bucket.host/key}），RustFS 不认这种形态，
 *       必须改用路径风格 {@code http://host/bucket/key}。</li>
 *   <li><b>{@code region} 不能省</b>：它参与 SigV4 签名计算，
 *       即使 RustFS 不校验区域，缺了它 SDK 会直接拒绝构造请求。</li>
 *   <li><b>{@code endpoint} 是 S3 端口（9000），不是控制台端口（9001）</b>：
 *       写错会得到一个像网络故障的解析错误。</li>
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "app.storage")
public class ObjectStorageProperties {

    /** S3 API 端点，如 http://localhost:9000 */
    private String endpoint = "http://localhost:9000";

    private String accessKey;

    private String secretKey;

    /** 区域。RustFS 不校验，但 SigV4 签名必须有值 */
    private String region = "us-east-1";

    /** 文档原件所在桶；启动时会确保存在 */
    private String bucket = "erp-knowledge";

    /** 预签名上传链接有效期（分钟）。短期有效，避免链接被转发后长期可用 */
    private int presignValidMinutes = 15;

}
