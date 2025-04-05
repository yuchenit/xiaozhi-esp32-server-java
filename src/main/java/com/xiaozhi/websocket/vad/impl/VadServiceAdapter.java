package com.xiaozhi.websocket.vad.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.xiaozhi.websocket.vad.VadDetector;
import com.xiaozhi.websocket.service.VadService;

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
        return vadService.processAudio(sessionId, pcmData);
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