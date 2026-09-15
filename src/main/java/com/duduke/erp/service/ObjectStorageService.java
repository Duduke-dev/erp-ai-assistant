package com.duduke.erp.service;

import java.time.Duration;
import java.util.UUID;

import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.config.ObjectStorageProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 对象存储（RustFS）读写。
 *
 * <h3>对象键按租户隔离</h3>
 * 键统一为 {@code {entCode}/{uuid}/{fileName}}。前缀带租户是刻意的：
 * 桶是全租户共用的，若键不带租户前缀，一旦某处按前缀删除或列举，
 * 就会跨租户影响——这类事故在对象存储里没有事务可以回滚。
 *
 * <h3>桶按需创建</h3>
 * 首次使用时确保桶存在（{@link #ensureBucketIfAbsent}），且<b>失败只记 warn 不阻断启动</b>：
 * 对象存储不可用时应表现为"上传报错"，而不是"整个应用起不来"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    private final S3Client s3Client;

    private final S3Presigner s3Presigner;

    private final ObjectStorageProperties properties;

    /**
     * 当前桶名。存进 {@code knowledge_document.bucket}，
     * 让文档记录自带定位信息——换桶或换存储产品时，历史行仍能指向正确的位置。
     */
    public String bucketName() {
        return this.properties.getBucket();
    }

    /**
     * 生成对象键，按租户隔离。
     *
     * @param entCode  租户编码
     * @param fileName 原始文件名（仅取最后一段，防止路径穿越）
     */
    public String buildKey(String entCode, String fileName) {
        String safeName = sanitizeFileName(fileName);
        return (StringUtils.hasText(entCode) ? entCode : "unknown")
                + "/" + UUID.randomUUID().toString().replace("-", "")
                + "/" + safeName;
    }

    /**
     * 上传对象。
     *
     * @return 对象键（调用方应持久化它，后续靠它取回原件）
     */
    public String put(String objectKey, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new BusinessException("上传内容不能为空");
        }
        ensureBucketIfAbsent();
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(this.properties.getBucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .build();
            this.s3Client.putObject(request, RequestBody.fromBytes(content));
            return objectKey;
        }
        catch (S3Exception e) {
            // 存 500 而非 400：这是服务端依赖不可用，不是用户输入有误
            throw new BusinessException(500, "对象存储上传失败：" + e.getMessage(), e);
        }
    }

    /** 下载对象；对象不存在返回 null，由调用方决定如何提示 */
    public byte[] get(String objectKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(this.properties.getBucket())
                    .key(objectKey)
                    .build();
            ResponseBytes<?> bytes = this.s3Client.getObjectAsBytes(request);
            return bytes.asByteArray();
        }
        catch (NoSuchBucketException | software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return null;
        }
        catch (S3Exception e) {
            throw new BusinessException(500, "对象存储读取失败：" + e.getMessage(), e);
        }
    }

    /** 删除对象。已不存在视为成功——删除应当幂等 */
    public void remove(String objectKey) {
        try {
            this.s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(this.properties.getBucket())
                    .key(objectKey)
                    .build());
        }
        catch (NoSuchBucketException e) {
            // 桶都不存在，对象自然不存在。删除必须幂等：把"本来就没东西"
            // 当成失败，会让调用方在清理路径上被迫吞异常
            log.debug("删除时桶不存在，视为已删除：bucket={}, key={}",
                    this.properties.getBucket(), objectKey);
        }
        catch (S3Exception e) {
            throw new BusinessException(500, "对象存储删除失败：" + e.getMessage(), e);
        }
    }

    /**
     * 生成预签名上传链接：让前端直传对象存储，避免文件先经过应用再转发一遍。
     * <p>
     * 有效期取配置值（默认 15 分钟）——短期有效，链接即使被转发也很快失效。
     */
    public String presignPut(String objectKey, String contentType) {
        ensureBucketIfAbsent();
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(this.properties.getBucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .build();
            PresignedPutObjectRequest presigned = this.s3Presigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(
                                    this.properties.getPresignValidMinutes()))
                            .putObjectRequest(putRequest)
                            .build());
            return presigned.url().toString();
        }
        catch (RuntimeException e) {
            throw new BusinessException(500, "预签名生成失败：" + e.getMessage(), e);
        }
    }

    /**
     * 确保桶存在。
     * <p>
     * 失败只记 warn：桶建不出来应表现为"上传时报错"，
     * 而不是让整个应用启动失败——助手的主链路不依赖对象存储。
     */
    public void ensureBucketIfAbsent() {
        String bucket = this.properties.getBucket();
        try {
            this.s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        }
        catch (NoSuchBucketException e) {
            try {
                this.s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("对象存储桶已创建：{}", bucket);
            }
            catch (S3Exception createError) {
                log.warn("创建对象存储桶失败（不影响应用启动）：bucket={}, err={}",
                        bucket, createError.getMessage());
            }
        }
        catch (S3Exception e) {
            log.warn("探测对象存储桶失败（不影响应用启动）：bucket={}, err={}", bucket, e.getMessage());
        }
    }

    /**
     * 只取文件名最后一段，剥掉任何路径成分。
     * <p>
     * 不剥的话，{@code ../../x} 这类名字会写进对象键，
     * 在按前缀列举或删除时逃出预期目录——对象存储没有路径归一化保护。
     */
    private String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "unnamed";
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.isBlank() ? "unnamed" : name;
    }

}
