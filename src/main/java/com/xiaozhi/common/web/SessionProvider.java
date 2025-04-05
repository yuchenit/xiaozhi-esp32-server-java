package com.xiaozhi.common.web;

import java.io.Serializable;

import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Session提供者
 */
public interface SessionProvider {
    
    /**
     * 获取会话属性
     */
    Mono<Serializable> getAttribute(ServerWebExchange exchange, String name);
    
    /**
     * 设置会话属性
     */
    Mono<Void> setAttribute(ServerWebExchange exchange, String name, Serializable value);
    
    /**
     * 获取会话ID
     */
    Mono<String> getSessionId(ServerWebExchange exchange);
    
    /**
     * 登出
     */
    Mono<Void> logout(ServerWebExchange exchange);
    
    /**
     * 移除会话属性
     */
    Mono<Void> removeAttribute(ServerWebExchange exchange, String name);
}