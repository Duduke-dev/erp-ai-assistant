package com.duduke.erp.component.context;

import com.duduke.erp.tenant.TenantContextAccessor;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Hooks;

import java.util.concurrent.Executor;

/**
 * 上下文传播配置。
 * <p>
 * 把租户上下文注册到 Micrometer ContextRegistry，并开启 Reactor 自动上下文传播，
 * 使 SSE 流式和异步线程池场景都能正确恢复租户标识。
 */
@Slf4j
@Configuration
public class ContextPropagationConfig {

    @PostConstruct
    public void registerAccessors() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new TenantContextAccessor());
        Hooks.enableAutomaticContextPropagation();
        log.info("已注册租户上下文传播，Reactor 自动上下文传播已开启");
    }

    /**
     * 带上下文传播的异步执行器，用于文档解析等后台任务。
     *
     * @return 异步执行器
     */
    @Bean("ragTaskExecutor")
    public Executor ragTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("rag-task-");
        TaskDecorator decorator = new ContextPropagatingTaskDecorator();
        executor.setTaskDecorator(decorator);
        executor.initialize();
        return executor;
    }

}
