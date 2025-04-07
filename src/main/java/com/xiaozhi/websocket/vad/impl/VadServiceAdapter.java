package com.xiaozhi.websocket.vad.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.xiaozhi.websocket.vad.VadDetector;
import com.xiaozhi.websocket.service.VadService;
import com.xiaozhi.websocket.service.VadService.VadResult;

/**
 * VadDetector接口的适配器，连接到新的VadService实现
 * 这个适配器是为了保持向后兼容性，同时使用新的VadService架构
 */
@Component
public class VadServiceAdapter implements VadDetector {

    @Autowired
    private VadService vadService;

    @Override
    public void initializeSession(String sessionId) {
        vadService.initializeSession(sessionId);
    }

    @Override
    public byte[] processAudio(String sessionId, byte[] pcmData) {
        try {
            // 调用VadService处理音频并获取VadResult
            VadResult result = vadService.processAudio(sessionId, pcmData);

            // 如果结果为null或处理出错，返回原始数据
            if (result == null || result.getProcessedData() == null) {
                return pcmData;
            }

            // 返回处理后的音频数据
            return result.getProcessedData();
        } catch (Exception e) {
            // 发生异常时返回原始数据
            return pcmData;
        }
    }

    @Override
    public void setThreshold(float threshold) {
        vadService.setSpeechThreshold(threshold);
    }

    @Override
    public void resetSession(String sessionId) {
        vadService.resetSession(sessionId);
    }

    @Override
    public boolean isSpeaking(String sessionId) {
        return vadService.isSpeaking(sessionId);
    }

    @Override
    public float getCurrentSpeechProbability(String sessionId) {
        return vadService.getCurrentSpeechProbability(sessionId);
    }
}