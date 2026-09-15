package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.DocumentParseDeadLetter;

/**
 * 解析死信登记数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：本表有 {@code ent_code}，
 * 租户条件由插件注入——一个租户不该看到别家的失败任务（尤其 {@code payload}
 * 里带着文档名与对象键）。
 */
public interface DocumentParseDeadLetterMapper extends BaseMapper<DocumentParseDeadLetter> {
}
