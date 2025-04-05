package com.xiaozhi.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;

import com.xiaozhi.common.interceptor.AuthenticationInterceptor;
import com.xiaozhi.common.interceptor.LogInterceptor;

import javax.annotation.Resource;

@Configuration
public class WebFilterConfig {

    @Resource
    private AuthenticationInterceptor authenticationInterceptor;

    @Resource
    private LogInterceptor logInterceptor;

    /**
     * 认证过滤器
     */
    @Bean
    public WebFilter authenticationFilter() {
        return authenticationInterceptor;
    }

    /**
     * 日志过滤器
     */
    @Bean
    public WebFilter logFilter() {
        return logInterceptor;
    }
}