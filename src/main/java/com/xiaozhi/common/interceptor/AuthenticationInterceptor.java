package com.xiaozhi.common.interceptor;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.common.web.AjaxResult;
import com.xiaozhi.entity.SysUser;
import com.xiaozhi.service.SysUserService;
import com.xiaozhi.utils.CmsUtils;

import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

@Component
public class AuthenticationInterceptor implements WebFilter {

    @Resource
    private SysUserService userService;

    private static final Logger log = LoggerFactory.getLogger(AuthenticationInterceptor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 不需要认证的路径
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/api/user/login", 
            "/api/user/update", 
            "/api/device/ota", 
            "/audio/",
            "/ws/");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        
        // 检查是否是公共路径
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }
        
        // 获取会话
        return exchange.getSession()
                .flatMap(session -> {
                    // 检查会话中是否有用户
                    Object userObj = session.getAttribute(SysUserService.USER_SESSIONKEY);
                    if (userObj != null) {
                        SysUser user = (SysUser) userObj;
                        // 将用户信息存储在请求属性中
                        exchange.getAttributes().put(CmsUtils.USER_ATTRIBUTE_KEY, user);
                        return chain.filter(exchange);
                    }
                    
                    // 尝试从Cookie中获取用户名
                    return tryAuthenticateWithCookies(exchange, session)
                            .flatMap(authenticated -> {
                                if (authenticated) {
                                    return chain.filter(exchange);
                                }
                                return handleUnauthorized(exchange);
                            });
                });
    }
    
    /**
     * 尝试使用Cookie进行认证
     */
    private Mono<Boolean> tryAuthenticateWithCookies(ServerWebExchange exchange, WebSession session) {
        ServerHttpRequest request = exchange.getRequest();
        
        // 检查是否有username cookie
        HttpCookie usernameCookie = request.getCookies().getFirst("username");
        if (usernameCookie != null) {
            String username = usernameCookie.getValue();
            if (StringUtils.isNotBlank(username)) {
                SysUser user = userService.selectUserByUsername(username);
                if (user != null) {
                    // 将用户存储在会话和请求属性中
                    session.getAttributes().put(SysUserService.USER_SESSIONKEY, user);
                    exchange.getAttributes().put(CmsUtils.USER_ATTRIBUTE_KEY, user);
                    return Mono.just(true);
                }
            }
        }
        
        return Mono.just(false);
    }
    
    /**
     * 处理未授权的请求
     */
    private Mono<Void> handleUnauthorized(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        
        // 检查是否是AJAX请求
        String ajaxTag = request.getHeaders().getFirst("Request-By");
        String head = request.getHeaders().getFirst("X-Requested-With");
        
        if ((ajaxTag != null && ajaxTag.trim().equalsIgnoreCase("Ext")) 
                || (head != null && !head.equalsIgnoreCase("XMLHttpRequest"))) {
            response.getHeaders().add("_timeout", "true");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return Mono.empty();
        } else {
            // 返回JSON格式的错误信息
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
            
            AjaxResult result = AjaxResult.error(com.xiaozhi.common.web.HttpStatus.FORBIDDEN, "用户未登录");
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(result);
                return response.writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            } catch (Exception e) {
                log.error("写入响应失败", e);
                return Mono.error(e);
            }
        }
    }
    
    /**
     * 检查是否是公共路径
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}