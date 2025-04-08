package com.xiaozhi.websocket.audio.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.xiaozhi.websocket.audio.detector.impl.SileroVadDetector;
import com.xiaozhi.websocket.audio.processor.TarsosNoiseReducer;

@Configuration
public class AudioProcessingConfig {

    @Autowired
    private SileroVadDetector sileroVadDetector;

    @Autowired(required = false)
    private TarsosNoiseReducer tarsosNoiseReducer;

    @EventListener(ApplicationReadyEvent.class)
    public void setupAudioProcessing() {
        // 默认启用TarsosDSP降噪
        if (tarsosNoiseReducer != null) {
            sileroVadDetector.setEnableNoiseReduction(true);

            // 配置TarsosDSP降噪参数
            // 频谱减法因子: 通常在0到2之间。值越大，降噪效果越强.
            // 噪声估计帧数: 这个参数决定了用于估计背景噪声的帧数
            tarsosNoiseReducer.setSpectralSubtractionFactor(1);
            tarsosNoiseReducer.setNoiseEstimationFrames(10);
        }
    }
}