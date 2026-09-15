package com.duduke.erp.component.rustfs;

import java.net.URI;

import com.duduke.erp.config.ObjectStorageProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RustFS（S3 兼容）客户端装配。
 * <p>
 * 放在 {@code component/rustfs/} 而非 {@code config/}：本项目的规约是
 * {@code config/} 只放 {@code @ConfigurationProperties}，
 * 第三方组件的 {@code @Configuration} / {@code @Bean} 一律按组件名归到 {@code component/}。
 *
 * <h3>两处容易写错、且报错都不直观的地方</h3>
 * <ol>
 *   <li><b>{@code forcePathStyle(true)}</b>：SDK 默认走虚拟主机风格
 *       {@code http://bucket.host/key}，RustFS 只认路径风格
 *       {@code http://host/bucket/key}。不加这一行，请求会以一个"域名解析失败"
 *       的形式失败，看起来像网络问题。</li>
 *   <li><b>{@code region} 必填</b>：它参与 SigV4 签名计算。RustFS 不校验区域，
 *       但 SDK 在构造请求时就必须知道它，缺了会直接抛异常。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(ObjectStorageProperties properties) {
        return S3Client.builder()
                // endpointOverride 覆盖默认的 AWS 域名，指向 RustFS 的 S3 端口（9000）
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                properties.getAccessKey(), properties.getSecretKey())))
                .forcePathStyle(true)
                .build();
    }

    /**
     * 预签名器。单独一个 bean 而非复用 {@link S3Client}：
     * SDK 里预签名是独立类型，混用会拿到不可用的签名。
     */
    @Bean
    public S3Presigner s3Presigner(ObjectStorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                properties.getAccessKey(), properties.getSecretKey())))
                // 关键：S3Presigner 是**独立**于 S3Client 构建的，
                // 在 Client 上设 forcePathStyle 对它没有任何影响。
                // 漏了这行，签名出来的链接会退回虚拟主机风格，与 RustFS 不兼容。
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

}
