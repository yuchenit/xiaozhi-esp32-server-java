package com.xiaozhi.websocket.stt.factory;

import cn.hutool.extra.spring.SpringUtil;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.stt.providers.AliyunSttService;
import com.xiaozhi.websocket.stt.providers.TencentSttService;
import com.xiaozhi.websocket.stt.providers.VoskSttService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

// 服务注册组件
@Slf4j
@Component
class SttServiceProviderRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    public Optional<SttService> createService(String provider, SysConfig config) {
        try {
            switch (provider.toLowerCase()) {
                case "tencent":
                    return Optional.of(new TencentSttService(config));
                case "aliyun":
                    return Optional.of(new AliyunSttService(config));
                case "vosk":
                    return Optional.of(applicationContext.getBean(VoskSttService.class));
                default:
                    log.warn("Unregistered STT provider: {}", provider);
                    return Optional.empty();
            }
        } catch (Exception e) {
            log.error("Failed to create service for provider: {}", provider, e);
            return Optional.empty();
        }
    }
}