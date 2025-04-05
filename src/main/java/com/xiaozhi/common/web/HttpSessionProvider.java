package com.xiaozhi.common.web;

import java.io.Serializable;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;

import reactor.core.publisher.Mono;

/**
 * WebFlux会话提供类
 */
@Service
public class HttpSessionProvider implements SessionProvider {

    @Override
    public Mono<Serializable> getAttribute(ServerWebExchange exchange, String name) {
        return exchange.getSession()
                .map(session -> (Serializable) session.getAttribute(name));
    }

    @Override
    public Mono<Void> setAttribute(ServerWebExchange exchange, String name, Serializable value) {
        return exchange.getSession()
                .doOnNext(session -> session.getAttributes().put(name, value))
                .then();
    }

    @Override
    public Mono<String> getSessionId(ServerWebExchange exchange) {
        return exchange.getSession()
                .map(WebSession::getId);
    }

    @Override
    public Mono<Void> logout(ServerWebExchange exchange) {
        return exchange.getSession()
                .flatMap(WebSession::invalidate);
    }

    @Override
    public Mono<Void> removeAttribute(ServerWebExchange exchange, String name) {
        return exchange.getSession()
                .doOnNext(session -> session.getAttributes().remove(name))
                .then();
    }
}