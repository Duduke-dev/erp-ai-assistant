package com.duduke.erp.entity.dto;

import java.util.List;

/**
 * 文档版本晋级结果。
 *
 * @param supersededVersions 本次晋级时被置换掉的旧版本号，调用方需清理其向量
 * @param chunkCount         晋级版本的分片数
 * @param status             最终状态：ready 或 superseded
 *                           （迟到版本会发现已有更新的 ready 版本，此时自己转为 superseded）
 */
public record DocumentPromotionResult(
        List<Integer> supersededVersions,
        Integer chunkCount,
        String status) {
}
