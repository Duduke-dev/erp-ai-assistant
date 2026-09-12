package com.duduke.erp.common.exception;

import java.util.stream.Collectors;

import com.duduke.erp.common.response.Result;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 * <p>
 * 目标：任何出口的异常都必须是 {@link Result} 结构，且 HTTP 状态码语义正确。
 * <p>
 * 有两个容易漏掉的点：
 * <ol>
 *   <li><b>Sa-Token 鉴权异常必须显式接管</b>，否则会走 Spring 默认错误页，
 *       响应结构与业务响应不一致。</li>
 *   <li><b>404 / 405 / 415 必须单独处理</b>。它们不是"服务端错误"，
 *       若只留 {@code Exception} 兜底会被统一转成 500，状态码全错。
 *       {@code NoResourceFoundException} 是 Spring 6.1+ 静态资源找不到时抛出的，
 *       正是 404 的主要来源。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    /**
     * 业务异常，错误码由抛出方决定。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常[{}]: {}", ex.getCode(), ex.getMessage());
        // resolve 对非标准状态码返回 null 而非抛异常，此处降级为 400
        HttpStatus status = HttpStatus.resolve(ex.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    // ==================== 鉴权异常 ====================

    /**
     * 未登录或登录已过期。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException ex) {
        log.warn("未登录访问: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Result.fail(401, "未登录或登录已过期"));
    }

    /**
     * 缺少所需权限码，对应 {@code @SaCheckPermission} 校验失败。
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Result<Void>> handleNotPermission(NotPermissionException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Result.fail(403, "无访问权限"));
    }

    /**
     * 缺少所需角色，对应 {@code @SaCheckRole} 校验失败。
     */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<Void>> handleNotRole(NotRoleException ex) {
        log.warn("角色不足: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Result.fail(403, "角色权限不足"));
    }

    /**
     * 其余 Sa-Token 异常的兜底（账号封禁等），避免落到 500。
     */
    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<Result<Void>> handleSaToken(SaTokenException ex) {
        log.warn("鉴权异常: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Result.fail(403, "鉴权失败"));
    }

    // ==================== 请求参数异常 ====================

    /**
     * 参数绑定或校验失败。
     * <p>
     * {@code MethodArgumentNotValidException} 是 {@code BindException} 的子类，
     * 因此一并覆盖。这里把字段级错误拼出来，便于前端定位。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        String message = detail.isEmpty() ? "请求参数校验失败" : detail;
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.badRequest().body(Result.fail(400, message));
    }

    /**
     * 方法级参数校验失败（{@code @Validated} 作用在方法参数上）。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
        log.warn("方法参数校验失败: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求参数校验失败"));
    }

    /**
     * 缺少必填的请求参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("缺少请求参数: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
            .body(Result.fail(400, "缺少必填参数: " + ex.getParameterName()));
    }

    /**
     * 请求体不可读（JSON 格式错误、类型不匹配等）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求体格式错误"));
    }

    /**
     * 业务状态非法，如租户上下文缺失。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("业务状态异常: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, ex.getMessage()));
    }

    /**
     * 参数非法。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数非法: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, ex.getMessage()));
    }

    // ==================== 路由与协议异常 ====================

    /**
     * 资源不存在。
     * <p>
     * Spring 6.1+ 在静态资源与路由都匹配不到时抛此异常，是 404 的主要来源。
     * 不单独处理会被 {@code Exception} 兜底成 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Result.fail(404, "请求的资源不存在"));
    }

    /**
     * 请求方法不支持，如用 GET 访问 POST 接口。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(Result.fail(405, "请求方法不支持: " + ex.getMethod()));
    }

    /**
     * 请求的媒体类型不支持。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(Result.fail(415, "不支持的 Content-Type"));
    }

    // ==================== 数据访问异常 ====================

    /**
     * 唯一约束冲突等数据完整性错误。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("数据唯一约束冲突: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Result.fail(409, "数据已存在，请勿重复提交"));
    }

    /**
     * 其他数据访问异常。不回显数据库细节，仅记录日志。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDataAccess(DataAccessException ex) {
        log.error("数据访问异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.fail(500, "数据访问异常，请稍后重试"));
    }

    // ==================== 兜底 ====================

    /**
     * 兜底异常处理，避免把栈信息直接暴露给前端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.fail(500, "服务内部异常，请稍后重试"));
    }

}
