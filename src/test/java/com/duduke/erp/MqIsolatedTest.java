package com.duduke.erp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 需要真实 MQ、但必须与"正在运行的应用实例"隔离的测试。
 *
 * <h3>为什么不能直接用生产队列名</h3>
 * 这些测试的验证方式是「投递 → 自己 {@code receiveAndConvert} 取回来」，
 * 前提是**队列上没有别的消费者**。而开发时后端应用往往就开着，
 * {@code DocumentParseConsumer} 正是那台队列的消费者——它会先把消息取走，
 * 测试于是拿到 null，报"投递没生效"，实则消息已被正常消费。
 * 这个前提以前没被写下来，直到应用常驻后才以"4 个无关测试变红"的形式浮现。
 *
 * <h3>做法</h3>
 * 整套拓扑（队列 / 死信队列 / 交换机 / 路由键）都换成 {@code .it} 后缀的一套，
 * 由 {@code RabbitMqConfig} 按这些属性声明；应用的拓扑完全不受影响。
 * 同时关掉监听器自启动：测试要自己取消息，不需要消费者。
 *
 * <p>新增任何"投递后自己取"的测试时，请用它而不是裸 {@code @SpringBootTest}。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.mq.document-exchange=erp.document.it",
        "app.mq.document-parse-queue=erp.document.parse.it",
        "app.mq.document-parse-dlq=erp.document.parse.it.dlq",
        "app.mq.document-parse-routing-key=document.parse.it",
})
public @interface MqIsolatedTest {
}
