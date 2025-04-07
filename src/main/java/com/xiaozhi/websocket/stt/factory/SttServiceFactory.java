package com.xiaozhi.websocket.stt.factory;

import cn.hutool.extra.spring.SpringUtil;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.stt.providers.VoskSttService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SttServiceFactory {
    private static final String DEFAULT_PROVIDER = "vosk";
    private final Map<String, SttService> serviceCache = new ConcurrentHashMap<>();
    
    private final SttServiceProviderRegistry providerRegistry;

    @Autowired
    public SttServiceFactory(SttServiceProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public SttService getSttService(SysConfig config) {
        return Optional.ofNullable(config)
            .map(this::resolveConfiguredService)
            .orElseGet(this::getDefaultService);
    }

    private SttService resolveConfiguredService(SysConfig config) {
        String provider = config.getProvider();
        String configKey = buildConfigKey(config);

        return serviceCache.computeIfAbsent(configKey, k -> providerRegistry.createService(provider, config)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider: " + provider)));
    }

    private String buildConfigKey(SysConfig config) {
        return String.format("%s:%s",
            config.getProvider(),
            Optional.ofNullable(config.getConfigId()).orElse(-1)
        );
    }

    private SttService getDefaultService() {
        // 通过 resolveConfiguredService 获取，确保走缓存
        SysConfig dummyConfig = new SysConfig();  // 虚拟配置
        dummyConfig.setProvider(DEFAULT_PROVIDER);
        dummyConfig.setConfigId(-1);
        return resolveConfiguredService(dummyConfig);
    }
}