package com.xiaozhi.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于TarsosDSP的噪声抑制处理器
 * 使用简单的信号处理技术进行降噪
 */
@Component
public class TarsosNoiseReducer {
    private static final Logger logger = LoggerFactory.getLogger(TarsosNoiseReducer.class);

    // 采样率
    private final int sampleRate = 16000;

    // 每个处理块的大小
    private final int bufferSize = 512;

    // 噪声估计窗口大小（帧数）
    private int noiseEstimationFrames = 10;

    // 频谱减法因子
    private double spectralSubtractionFactor = 1.5;

    // 存储每个会话的噪声配置文件
    private final ConcurrentHashMap<String, float[]> sessionNoiseProfiles = new ConcurrentHashMap<>();

    // 存储每个会话的训练状态
    private final ConcurrentHashMap<String, Integer> sessionTrainingFrames = new ConcurrentHashMap<>();

    // 噪声地板
    private float noiseFloor = 0.01f;

    public TarsosNoiseReducer() {
        logger.info("噪声抑制处理器已初始化");
    }

    /**
     * 设置频谱减法因子
     * 
     * @param factor 新的因子值
     */
    public void setSpectralSubtractionFactor(double factor) {
        if (factor < 1.0 || factor > 3.0) {
            throw new IllegalArgumentException("频谱减法因子必须在1.0到3.0之间");
        }
        this.spectralSubtractionFactor = factor;
        logger.info("频谱减法因子已更新为: {}", factor);
    }

    /**
     * 设置噪声估计窗口大小
     * 
     * @param frames 帧数
     */
    public void setNoiseEstimationFrames(int frames) {
        if (frames < 1 || frames > 50) {
            throw new IllegalArgumentException("噪声估计窗口必须在1到50帧之间");
        }
        this.noiseEstimationFrames = frames;
        logger.info("噪声估计窗口已更新为: {} 帧", frames);
    }

    /**
     * 初始化会话的噪声减少器
     * 
     * @param sessionId 会话ID
     */
    public void initializeSession(String sessionId) {
        if (!sessionNoiseProfiles.containsKey(sessionId)) {
            sessionNoiseProfiles.put(sessionId, new float[bufferSize]);
            sessionTrainingFrames.put(sessionId, 0);
        }
    }

    /**
     * 处理PCM音频数据
     * 
     * @param sessionId 会话ID
     * @param pcmData   原始PCM音频数据
     * @return 处理后的PCM音频数据
     */
    public byte[] processAudio(String sessionId, byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2) {
            return pcmData;
        }

        // 确保会话已初始化
        if (!sessionNoiseProfiles.containsKey(sessionId)) {
            initializeSession(sessionId);
        }

        // 将PCM字节数据转换为short数组
        short[] samples = new short[pcmData.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((pcmData[i * 2] & 0xFF) | ((pcmData[i * 2 + 1] & 0xFF) << 8));
        }

        // 处理音频数据
        short[] processedSamples = processShortSamples(sessionId, samples);

        // 将处理后的short数组转换回字节数组
        byte[] processedPcm = new byte[processedSamples.length * 2];
        for (int i = 0; i < processedSamples.length; i++) {
            processedPcm[i * 2] = (byte) (processedSamples[i] & 0xFF);
            processedPcm[i * 2 + 1] = (byte) ((processedSamples[i] >> 8) & 0xFF);
        }

        return processedPcm;
    }

    /**
     * 处理short类型的音频样本
     * 
     * @param sessionId 会话ID
     * @param samples   原始音频样本
     * @return 处理后的音频样本
     */
    private short[] processShortSamples(String sessionId, short[] samples) {
        // 创建输出缓冲区
        short[] output = new short[samples.length];

        // 获取会话的噪声配置文件和训练状态
        float[] noiseProfile = sessionNoiseProfiles.get(sessionId);
        int trainingFrames = sessionTrainingFrames.get(sessionId);

        // 确定要处理的块数
        int blockCount = (samples.length + bufferSize - 1) / bufferSize;

        // 转换为float数组进行处理
        float[] floatSamples = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            floatSamples[i] = samples[i] / 32767.0f;
        }

        // 处理每个块
        for (int i = 0; i < blockCount; i++) {
            int offset = i * bufferSize;
            int length = Math.min(bufferSize, samples.length - offset);

            if (length > 0) {
                // 提取当前块
                float[] buffer = new float[bufferSize];
                for (int j = 0; j < length; j++) {
                    buffer[j] = (j < length) ? floatSamples[offset + j] : 0.0f;
                }

                // 如果在训练阶段，更新噪声配置文件
                if (trainingFrames < noiseEstimationFrames) {
                    updateNoiseProfile(noiseProfile, buffer, trainingFrames);
                    trainingFrames++;
                    sessionTrainingFrames.put(sessionId, trainingFrames);

                    // 训练阶段直接返回原始数据
                    for (int j = 0; j < length; j++) {
                        output[offset + j] = samples[offset + j];
                    }
                } else {
                    // 应用噪声抑制
                    float[] processedBuffer = applyNoiseReduction(buffer, noiseProfile);

                    // 转换回short
                    for (int j = 0; j < length; j++) {
                        output[offset + j] = (short) (processedBuffer[j] * 32767.0f);
                    }
                }
            }
        }

        return output;
    }

    /**
     * 更新噪声配置文件
     */
    private void updateNoiseProfile(float[] noiseProfile, float[] buffer, int trainingFrames) {
        for (int i = 0; i < buffer.length; i++) {
            // 使用指数移动平均更新噪声配置文件
            if (trainingFrames == 0) {
                noiseProfile[i] = Math.abs(buffer[i]);
            } else {
                noiseProfile[i] = 0.8f * noiseProfile[i] + 0.2f * Math.abs(buffer[i]);
            }
        }
    }

    /**
     * 应用噪声抑制
     */
    private float[] applyNoiseReduction(float[] buffer, float[] noiseProfile) {
        float[] result = new float[buffer.length];

        for (int i = 0; i < buffer.length; i++) {
            // 计算当前样本的幅度
            float magnitude = Math.abs(buffer[i]);

            // 如果幅度小于噪声阈值的spectralSubtractionFactor倍，则减弱信号
            float threshold = noiseProfile[i] * (float) spectralSubtractionFactor;
            if (magnitude < threshold) {
                // 应用软门限，而不是简单地将信号设为零
                float gain = (magnitude / threshold);
                gain = gain * gain; // 平方以获得更陡峭的曲线
                result[i] = buffer[i] * gain;
            } else {
                result[i] = buffer[i];
            }

            // 确保信号不会小于噪声地板
            if (Math.abs(result[i]) < noiseFloor) {
                result[i] *= 0.1f; // 降低非常小的值，而不是完全消除
            }
        }

        return result;
    }

    /**
     * 重置会话的噪声估计
     * 
     * @param sessionId 会话ID
     */
    public void resetNoiseEstimate(String sessionId) {
        sessionNoiseProfiles.put(sessionId, new float[bufferSize]);
        sessionTrainingFrames.put(sessionId, 0);
        logger.info("会话 {} 的噪声估计已重置", sessionId);
    }

    /**
     * 清理会话资源
     * 
     * @param sessionId 会话ID
     */
    public void cleanupSession(String sessionId) {
        sessionNoiseProfiles.remove(sessionId);
        sessionTrainingFrames.remove(sessionId);
        logger.info("会话 {} 的噪声减少器资源已清理", sessionId);
    }
}