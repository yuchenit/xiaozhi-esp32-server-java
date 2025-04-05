package com.xiaozhi.common.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * 系统日志拦截器
 * 
 * @author Joey
 * 
 */
@Component
public class LogInterceptor implements WebFilter {
    
    private static final Logger logger = LoggerFactory.getLogger(LogInterceptor.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethodValue();
        String remoteAddress = exchange.getRequest().getRemoteAddress() != null ? 
                exchange.getRequest().getRemoteAddress().getHostString() : "unknown";

        return chain.filter(exchange)
                .doFinally(signalType -> {
                    // logger.info("Response: {} {} - {}", method, path, exchange.getResponse().getStatusCode());
                });
    }
}