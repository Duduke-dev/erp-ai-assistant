package com.duduke.erp.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多租户配置。
 */
@Data
@ConfigurationProperties(prefix = "app.tenant")
public class TenantProperties {

    /** 租户列名，MyBatis-Plus 租户插件会自动给租户隔离表追加该列条件。 */
    private String column = "ent_code";

    /** 全局配置表，没有租户列，必须跳过租户条件注入。 */
    private List<String> ignoreTables = new ArrayList<>();

}
