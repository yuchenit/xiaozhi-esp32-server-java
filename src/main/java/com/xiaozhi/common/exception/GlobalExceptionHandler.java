package com.xiaozhi.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import com.xiaozhi.common.web.AjaxResult;

import reactor.core.publisher.Mono;

/**
 * 全局异常处理器
 * 
 * @author Joey
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 用户名不存在异常
     */
    @ExceptionHandler(UsernameNotFoundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AjaxResult> handleUsernameNotFoundException(UsernameNotFoundException e, ServerWebExchange exchange) {
        log.error(e.getMessage(), e);
        return Mono.just(AjaxResult.error("用户名不存在"));
    }

    /**
     * 用户密码不匹配异常
     */
    @ExceptionHandler(UserPasswordNotMatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AjaxResult> handleUserPasswordNotMatchException(UserPasswordNotMatchException e, ServerWebExchange exchange) {
        log.error(e.getMessage(), e);
        return Mono.just(AjaxResult.error("用户密码不正确"));
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<AjaxResult> handleException(Exception e, ServerWebExchange exchange) {
        log.error(e.getMessage(), e);
        return Mono.just(AjaxResult.error("服务器错误，请联系管理员"));
    }
}