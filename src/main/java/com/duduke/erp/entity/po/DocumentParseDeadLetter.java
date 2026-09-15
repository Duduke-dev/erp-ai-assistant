package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 解析死信登记。
 * <p>
 * 消费失败且不再重试的消息会转投死信队列，本表是它在应用内的影子：
 * 没有这张表，死信只存在于 RabbitMQ 中——应用内看不见、无法重投，
 * 用户端就表现为"上传失败了但没人知道为什么、也没法恢复"。
 *
 * <h3>{@code payload} 原样保存</h3>
 * 重投时把它原样发回主队列，而不是按字段重新拼一条消息：
 * 重新拼等于假设「字段集合永远等于消息结构」，多一份真相就多一处错位。
 *
 * <h3>不做物理删除</h3>
 * 丢弃只是把 {@code status} 置为 {@code discarded}，记录留着——
 * 死信本身就是故障证据，删掉等于把排查线索一起丢了。
 */
@Data
@TableName("document_parse_dead_letter")
public class DocumentParseDeadLetter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String documentId;

    private Long knowledgeBaseId;

    private Integer version;

    private String objectKey;

    private String fileName;

    private String payload;

    private String errorMessage;

    /** pending / retried / discarded */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
