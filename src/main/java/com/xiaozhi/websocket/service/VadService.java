package com.xiaozhi.websocket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.xiaozhi.utils.OpusProcessor;
import com.xiaozhi.utils.TarsosNoiseReducer;
import com.xiaozhi.websocket.vad.VadModel;
import com.xiaozhi.websocket.vad.VadSessionState;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VAD服务 - 负责语音活动检测的核心功能
 */
@Service
public class VadService {
    private static final Logger logger = LoggerFactory.getLogger(VadService.class);

    @Autowired
    private VadModel vadModel;

    @Autowired
    private OpusProcessor opusProcessor;

    @Autowired(required = false)
    private TarsosNoiseReducer tarsosNoiseReducer;

    // 配置参数
    private float speechThreshold = 0.6f;
    private float silenceThreshold = 0.45f; // speechThreshold - 0.15f
    private float energyThreshold = 0.05f;
    private int minSilenceDurationMs = 500;
    private int maxSilenceDurationMs = 1000;
    private boolean enableNoiseReduction = true;

    // 会话状态管理
    private final ConcurrentHashMap<String, VadSessionState> sessionStates = new ConcurrentHashMap<>();

    // 会话锁管理 - 为每个会话提供独立的锁对象
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 初始化噪声抑制器
     */
    @PostConstruct
    private void initializeNoiseReducer() {
        if (tarsosNoiseReducer != null) {
            // 根据VAD的需求调整噪声抑制参数
            tarsosNoiseReducer.setSpectralSubtractionFactor(1.5); // 设置适中的频谱减法因子
            tarsosNoiseReducer.setNoiseEstimationFrames(10); // 设置噪声估计窗口大小
            logger.info("TarsosDSP噪声抑制器参数已优化配置");
        }
    }

    /**
     * 获取会话的锁对象
     */
    private Object getSessionLock(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, id -> new Object());
    }

    /**
     * 初始化会话 - 只有当会话不存在时才进行初始化
     * 
     * @param sessionId 会话ID
     * @return 如果是新会话返回true，否则返回false
     */
    public boolean initializeSession(String sessionId) {
        Object lock = getSessionLock(sessionId);

        synchronized (lock) {
            // 检查会话是否已存在
            if (sessionStates.containsKey(sessionId)) {
                return false; // 会话已存在，不需要初始化
            }

            // 创建新会话
            sessionStates.put(sessionId, new VadSessionState());

            // 如果启用了降噪，初始化其会话
            if (enableNoiseReduction && tarsosNoiseReducer != null) {
                tarsosNoiseReducer.initializeSession(sessionId);
            }

            logger.debug("VAD会话初始化 - SessionId: {}", sessionId);
            return true; // 返回true表示这是一个新会话
        }
    }

    /**
     * 处理音频数据并检测语音活动
     * 
     * @param sessionId 会话ID
     * @param opusData  Opus编码的音频数据
     * @return 如果检测到语音结束，返回完整的PCM音频数据；否则返回null
     */
    public byte[] processAudio(String sessionId, byte[] opusData) {
        // 获取会话锁
        Object lock = getSessionLock(sessionId);

        synchronized (lock) {
            try {
                // 获取会话状态，如果不存在则创建
                VadSessionState state = sessionStates.get(sessionId);
                if (state == null) {
                    state = new VadSessionState();
                    sessionStates.put(sessionId, state);

                    // 如果启用了降噪，初始化其会话
                    if (enableNoiseReduction && tarsosNoiseReducer != null) {
                        tarsosNoiseReducer.initializeSession(sessionId);
                    }

                    logger.debug("VAD会话初始化（处理中） - SessionId: {}", sessionId);
                }

                // 解码Opus数据为PCM
                byte[] pcmData = opusProcessor.decodeOpusFrameToPcm(opusData);

                // 应用噪声抑制（如果启用）
                if (enableNoiseReduction) {
                    pcmData = applyNoiseReduction(sessionId, pcmData);
                }

                // 将PCM数据添加到预缓冲区
                state.addToPrebuffer(pcmData);

                // 如果已经在说话状态，则将数据添加到主缓冲区
                if (state.isSpeaking()) {
                    state.addToMainBuffer(pcmData);
                }

                // 分析音频帧
                if (state.hasEnoughDataForAnalysis()) {
                    float[] samples = state.extractSamplesForAnalysis();
                    float currentEnergy = calculateEnergy(samples);
                    state.updateAverageEnergy(currentEnergy);

                    // 使用VAD模型获取语音概率
                    float speechProb = vadModel.getSpeechProbability(samples);
                    state.addProbability(speechProb);

                    // 检查是否有显著能量
                    boolean hasSignificantEnergy = hasSignificantEnergy(currentEnergy, state.getAverageEnergy());

                    // 检测语音状态变化
                    if (speechProb >= speechThreshold && hasSignificantEnergy) {
                        state.incrementConsecutiveSpeechFrames();

                        // 检查是否满足语音开始条件
                        if (state.shouldStartSpeech()) {
                            state.setSpeaking(true);
                            state.transferPrebufferToMainBuffer();
                        }

                        if (state.isSpeaking()) {
                            state.resetSilenceCount();
                        }
                    } else {
                        state.resetConsecutiveSpeechFrames();

                        // 如果当前是说话状态，检测静音
                        if (state.isSpeaking() && speechProb < silenceThreshold) {
                            state.incrementSilenceFrames();

                            // 计算静音持续时间
                            int silenceDurationMs = state.getSilenceDurationMs();

                            // 如果静音时间超过阈值，认为语音结束
                            if (silenceDurationMs >= minSilenceDurationMs) {
                                byte[] completeAudio = state.getCompleteAudio();
                                byte[] processedResult = postProcessAudio(completeAudio);
                                state.reset();
                                return processedResult;
                            }
                        }
                    }

                    // 检查是否超过最大静音时间（防止无限等待）
                    if (state.isSpeaking() && state.getTimeSinceLastSpeech() > maxSilenceDurationMs) {
                        byte[] completeAudio = state.getCompleteAudio();
                        byte[] processedResult = postProcessAudio(completeAudio);
                        state.reset();
                        return processedResult;
                    }
                }

                // 如果没有检测到语音结束，返回null
                return null;
            } catch (Exception e) {
                logger.error("VAD处理音频失败 - SessionId: {}", sessionId, e);
                return null;
            }
        }
    }

    /**
     * 对录制的音频进行后处理，去除低能量部分
     */
    private byte[] postProcessAudio(byte[] audioData) {
        if (audioData == null || audioData.length < 4) {
            return audioData;
        }

        // 将字节数据转换为short数组
        short[] samples = new short[audioData.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((audioData[i * 2] & 0xFF) | ((audioData[i * 2 + 1] & 0xFF) << 8));
        }

        // 计算平均能量
        float avgEnergy = 0;
        for (short sample : samples) {
            avgEnergy += Math.abs(sample) / 32767.0f;
        }
        avgEnergy /= samples.length;

        // 找到高能量段的起始和结束位置
        int start = 0, end = samples.length - 1;
        float threshold = avgEnergy * 2.0f; // 能量阈值为平均能量的2倍

        // 从前向后找到第一个高能量点
        for (int i = 0; i < samples.length; i++) {
            if (Math.abs(samples[i]) / 32767.0f > threshold) {
                start = Math.max(0, i - 1600); // 向前预留0.1秒
                break;
            }
        }

        // 从后向前找到最后一个高能量点
        for (int i = samples.length - 1; i >= 0; i--) {
            if (Math.abs(samples[i]) / 32767.0f > threshold) {
                end = Math.min(samples.length - 1, i + 1600); // 向后预留0.1秒
                break;
            }
        }

        // 如果找不到高能量段，或者高能量段太短，返回原始数据
        if (end - start < 3200) { // 至少0.2秒
            return audioData;
        }

        // 截取高能量段
        int newLength = (end - start + 1) * 2;
        byte[] trimmedAudio = new byte[newLength];
        System.arraycopy(audioData, start * 2, trimmedAudio, 0, newLength);

        return trimmedAudio;
    }

    /**
     * 应用噪声抑制
     */
    private byte[] applyNoiseReduction(String sessionId, byte[] pcmData) {
        if (tarsosNoiseReducer != null && enableNoiseReduction) {
            return tarsosNoiseReducer.processAudio(sessionId, pcmData);
        }
        return pcmData;
    }

    /**
     * 计算音频样本的能量
     */
    private float calculateEnergy(float[] samples) {
        float energy = 0;
        for (float sample : samples) {
            energy += Math.abs(sample);
        }
        return energy / samples.length;
    }

    /**
     * 判断当前能量是否显著
     */
    private boolean hasSignificantEnergy(float currentEnergy, float averageEnergy) {
        return currentEnergy > averageEnergy * 1.5 && currentEnergy > energyThreshold;
    }

    /**
     * 重置会话状态
     */
    public void resetSession(String sessionId) {
        Object lock = getSessionLock(sessionId);

        synchronized (lock) {
            VadSessionState state = sessionStates.get(sessionId);
            if (state != null) {
                state.reset();
            }
            sessionStates.remove(sessionId);

            // 如果启用了降噪，重置其会话
            if (enableNoiseReduction && tarsosNoiseReducer != null) {
                tarsosNoiseReducer.cleanupSession(sessionId);
            }

            // 移除会话锁
            sessionLocks.remove(sessionId);

            logger.info("VAD会话重置 - SessionId: {}", sessionId);
        }
    }

    /**
     * 检查当前是否正在说话
     */
    public boolean isSpeaking(String sessionId) {
        Object lock = getSessionLock(sessionId);

        synchronized (lock) {
            VadSessionState state = sessionStates.get(sessionId);
            return state != null && state.isSpeaking();
        }
    }

    /**
     * 获取当前语音概率
     */
    public float getCurrentSpeechProbability(String sessionId) {
        Object lock = getSessionLock(sessionId);

        synchronized (lock) {
            VadSessionState state = sessionStates.get(sessionId);
            if (state != null && !state.getProbabilities().isEmpty()) {
                return state.getLastProbability();
            }
            return 0.0f;
        }
    }

    /**
     * 强制结束当前语音，返回已收集的音频数据
     */
    public byte[] forceEndSession(String sessionId) {
        Object lock = getSessionLock(sessionId);

        synchronized (lock) {
            VadSessionState state = sessionStates.get(sessionId);
            if (state != null && state.hasAudioData()) {
                byte[] completeAudio = state.getCompleteAudio();
                state.reset();
                return completeAudio;
            }
            return null;
        }
    }

    // Getter和Setter方法
    public void setSpeechThreshold(float threshold) {
        if (threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("语音阈值必须在0.0到1.0之间");
        }
        this.speechThreshold = threshold;
        this.silenceThreshold = threshold - 0.15f;
        logger.info("VAD语音阈值已更新为: {}, 静音阈值: {}", threshold, silenceThreshold);
    }

    public void setEnergyThreshold(float threshold) {
        if (threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("能量阈值必须在0.0到1.0之间");
        }
        this.energyThreshold = threshold;
        logger.info("能量阈值已更新为: {}", threshold);
    }

    public void setEnableNoiseReduction(boolean enable) {
        this.enableNoiseReduction = enable;
        logger.info("噪声抑制功能已{}", enable ? "启用" : "禁用");
    }
}