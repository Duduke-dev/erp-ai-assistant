package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 动态 LLM Tool 定义。
 * <p>
 * <b>这是全局配置表，没有 ent_code</b>：Tool 的定义（SQL 模板与参数 schema）
 * 由平台维护、所有租户共用一份，租户隔离发生在<b>执行时</b>——
 * 执行器往 SQL 里注入当前租户的 ent_code。
 * <p>
 * 因此本表已加入 {@code app.tenant.ignore-tables}，租户插件不会给它加条件；
 * 若漏加会导致管理端一条都查不到（静默消失，不报错）。
 */
@Data
@TableName("llm_tool")
public class LlmTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Tool 名称，全局唯一，下发给模型作为 function 名 */
    private String toolName;

    /** 给模型看的说明，决定模型何时选它 */
    private String toolDesc;

    /** 入参 JSON Schema，原样下发给模型 */
    private String inputSchema;

    /** 带 :name 命名参数的 SQL 模板，执行前转成 JDBC ? 绑定 */
    private String sqlTemplate;

    /** 主表别名，租户注入时拼成 alias.ent_code；为空则用裸列名 */
    private String tableAlias;

    /** 单次返回行数上限，1 ~ 500 */
    private Integer resultLimit;

    /** active / inactive，只有 active 会被注册给模型 */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
