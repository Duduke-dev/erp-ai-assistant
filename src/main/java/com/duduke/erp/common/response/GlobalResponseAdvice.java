package com.duduke.erp.common.response;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * 统一响应包装。
 * <p>
 * Controller 返回业务对象即可，这里自动包装成 {@link Result}，业务代码不需要关心响应结构。
 * <p>
 * 有三处必须特殊处理，否则会出问题：
 * <ol>
 *   <li><b>SSE 流式响应不能被包装</b>——{@code text/event-stream} 一旦被套上 Result
 *       外层，前端的 EventSource 会直接解析失败。流式接口（M4 的对话推送）全靠这条保护。</li>
 *   <li><b>String 返回值必须手动序列化</b>——返回类型为 String 时 Spring 选中的是
 *       {@code StringHttpMessageConverter}，若直接返回 Result 会抛
 *       {@code ClassCastException: Result cannot be cast to String}。</li>
 *   <li><b>已包装的不重复包装</b>——异常处理器返回的就是 Result，需原样透传。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        // 流式响应、二进制、静态资源不参与包装
        return !Flux.class.isAssignableFrom(type)
                && !ServerSentEvent.class.isAssignableFrom(type)
                && !Resource.class.isAssignableFrom(type)
                && !byte[].class.equals(type);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request, ServerHttpResponse response) {

        // 二次保护：即使 supports 漏判，SSE 也不能被包装
        if (selectedContentType != null
                && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(selectedContentType)) {
            return body;
        }
        if (body instanceof Result || body instanceof byte[] || body instanceof Resource) {
            return body;
        }

        Result<Object> result = Result.ok(body);

        if (body instanceof String) {
            // StringHttpMessageConverter 已选定，手动序列化并改写 Content-Type
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return toJson(result);
        }
        return result;
    }

    private String toJson(Result<?> result) {
        try {
            return this.objectMapper.writeValueAsString(result);
        }
        catch (Exception ex) {
            log.error("统一响应序列化失败", ex);
            throw new IllegalStateException("统一响应序列化失败", ex);
        }
    }

}
