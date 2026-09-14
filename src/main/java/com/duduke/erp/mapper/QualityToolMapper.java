package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 质检模块 Tool 的只读查询。
 * <p>
 * 约定同 {@link SalesToolMapper}：不写 {@code ent_code}、不加 {@code @InterceptorIgnore}，
 * 日期列 {@code TO_CHAR} 成 {@code yyyy-MM-dd}。
 */
public interface QualityToolMapper {

    /** 按批次号查质检结果。 */
    @Select("""
            SELECT inspection_no, batch_no, product_name, sample_qty, qualified_qty, defect_qty,
                   TO_CHAR(inspection_date, 'YYYY-MM-DD') AS inspection_date, result
            FROM quality_inspection
            WHERE batch_no = #{batchNo}
            ORDER BY inspection_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectInspectionByBatch(@Param("batchNo") String batchNo);

    /** 按产品名称模糊查质检记录。 */
    @Select("""
            SELECT inspection_no, batch_no, product_name, sample_qty, qualified_qty, defect_qty,
                   TO_CHAR(inspection_date, 'YYYY-MM-DD') AS inspection_date, result
            FROM quality_inspection
            WHERE product_name LIKE #{pattern}
            ORDER BY inspection_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectRecordsByProduct(@Param("pattern") String pattern);

    /** 按批次号查不良明细（关联质检单）。ON 带 ent_code 防跨租户同号串行。 */
    @Select("""
            SELECT i.inspection_no, i.batch_no, i.product_name,
                   d.defect_type, d.defect_qty, d.remark
            FROM quality_inspection i
            JOIN quality_defect_detail d
              ON d.ent_code = i.ent_code
             AND d.inspection_no = i.inspection_no
            WHERE i.batch_no = #{batchNo}
            ORDER BY d.defect_qty DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectDefectsByBatch(@Param("batchNo") String batchNo);

    /** 按检验日期范围查质检记录。 */
    @Select("""
            SELECT inspection_no, batch_no, product_name, sample_qty, qualified_qty, defect_qty,
                   TO_CHAR(inspection_date, 'YYYY-MM-DD') AS inspection_date, result
            FROM quality_inspection
            WHERE inspection_date BETWEEN #{from} AND #{to}
            ORDER BY inspection_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectRecordsByDateRange(@Param("from") LocalDate from,
                                                       @Param("to") LocalDate to);

    /**
     * 按时间范围统计合格率。
     * <p>
     * 用 {@code NULLIF(SUM(sample_qty), 0)} 兜住除零：区间内没有取样时返回 null，
     * 而不是抛错让整个 Tool 调用失败。
     */
    @Select("""
            SELECT COUNT(*)                                        AS inspection_count,
                   COALESCE(SUM(sample_qty), 0)                    AS total_sample,
                   COALESCE(SUM(qualified_qty), 0)                 AS total_qualified,
                   COALESCE(SUM(defect_qty), 0)                    AS total_defect,
                   ROUND(COALESCE(SUM(qualified_qty), 0) * 100.0
                         / NULLIF(SUM(sample_qty), 0), 2)          AS qualified_rate
            FROM quality_inspection
            WHERE inspection_date BETWEEN #{from} AND #{to}
            """)
    List<Map<String, Object>> selectQualityRate(@Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

}
