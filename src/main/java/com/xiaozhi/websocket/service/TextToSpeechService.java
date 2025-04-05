package com.xiaozhi.websocket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.websocket.tts.TtsService;
import com.xiaozhi.websocket.tts.factory.TtsServiceFactory;

@Service
public class TextToSpeechService {
    private static final Logger logger = LoggerFactory.getLogger(TextToSpeechService.class);

    @Autowired
    private TtsServiceFactory ttsServiceFactory;

    public String textToSpeech(String message) throws Exception {
        try {
            // 获取默认TTS服务
            TtsService ttsService = ttsServiceFactory.getTtsService();
            // 使用TTS服务将文本转换为语音
            return ttsService.textToSpeech(message);
        } catch (Exception e) {
            logger.error("文本转语音失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 将文本转换为语音
     * 
     * @param message    要转换为语音的文本
     * @param config     配置信息
     * @param voiceName  语音名称
     * @return 生成的音频文件路径
     */
    public String textToSpeech(String message, SysConfig config, String voiceName) throws Exception {
        // 获取默认TTS服务
        TtsService ttsService = ttsServiceFactory.getTtsService(config, voiceName);
        try {
            // 使用TTS服务将文本转换为语音
            return ttsService.textToSpeech(message);
        } catch (Exception e) {
            logger.error("文本转语音失败: {}", e.getMessage(), e);
            throw e;
        }
    }

}
