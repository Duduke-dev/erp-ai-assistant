package com.duduke.erp.component.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.duduke.erp.config.TenantProperties;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页插件 + 租户插件。
 * <p>
 * 注意：分页插件自 3.5.x 起内部依赖 jsqlparser，必须额外引入
 * {@code mybatis-plus-jsqlparser} 依赖，否则运行时报类缺失。
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
@RequiredArgsConstructor
public class ErpMybatisPlusConfig {

    private final TenantProperties tenantProperties;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public Expression getTenantId() {
                return new StringValue(TenantContext.requireEntCode());
            }

            @Override
            public String getTenantIdColumn() {
                return ErpMybatisPlusConfig.this.tenantProperties.getColumn();
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return ErpMybatisPlusConfig.this.tenantProperties.getIgnoreTables()
                    .contains(tableName.toLowerCase());
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

}
