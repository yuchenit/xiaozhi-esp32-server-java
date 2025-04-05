package com.xiaozhi.utils;

import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OpusProcessor {
    private static final Logger logger = LoggerFactory.getLogger(OpusProcessor.class);

    // 存储每个会话的解码器
    private final ConcurrentHashMap<String, OpusDecoder> sessionDecoders = new ConcurrentHashMap<>();

    // 默认的帧大小
    private static final int DEFAULT_FRAME_SIZE = 960; // Opus典型帧大小

    /**
     * 解码Opus帧为PCM数据
     */
    public byte[] decodeOpusFrameToPcm(byte[] opusData) throws OpusException {
        OpusDecoder decoder = new OpusDecoder(16000, 1); // 采样率16kHz，单声道
        short[] pcmBuffer = new short[DEFAULT_FRAME_SIZE];
        int samplesDecoded = decoder.decode(opusData, 0, opusData.length, pcmBuffer, 0, DEFAULT_FRAME_SIZE, false);

        // 转换为字节数组
        byte[] pcmBytes = new byte[samplesDecoded * 2];
        for (int i = 0; i < samplesDecoded; i++) {
            pcmBytes[i * 2] = (byte) (pcmBuffer[i] & 0xFF);
            pcmBytes[i * 2 + 1] = (byte) ((pcmBuffer[i] >> 8) & 0xFF);
        }

        return pcmBytes;
    }

    /**
     * 解码Opus帧为PCM数据（返回short数组）
     */
    public short[] decodeOpusFrame(byte[] opusData) throws OpusException {
        OpusDecoder decoder = new OpusDecoder(16000, 1); // 采样率16kHz，单声道
        short[] pcmBuffer = new short[DEFAULT_FRAME_SIZE];
        decoder.decode(opusData, 0, opusData.length, pcmBuffer, 0, DEFAULT_FRAME_SIZE, false);
        return pcmBuffer;
    }

    /**
     * 获取会话的Opus解码器（如果不存在则创建）
     */
    public OpusDecoder getSessionDecoder(String sessionId) {
        return sessionDecoders.computeIfAbsent(sessionId, k -> {
            try {
                return new OpusDecoder(16000, 1);
            } catch (OpusException e) {
                logger.error("创建Opus解码器失败", e);
                throw new RuntimeException("创建Opus解码器失败", e);
            }
        });
    }

    /**
     * 将PCM数据转换为Opus格式
     */
    public List<byte[]> convertPcmToOpus(byte[] pcmData, int sampleRate, int channels, int frameDurationMs)
            throws OpusException {
        // 创建Opus编码器
        OpusEncoder encoder = new OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP);

        // 设置比特率 (16kbps)
        encoder.setBitrate(16000);

        // 每帧样本数
        int frameSize = sampleRate * frameDurationMs / 1000;

        // 处理PCM数据
        List<byte[]> opusFrames = new ArrayList<>();
        short[] shortBuffer = new short[frameSize * channels];

        for (int i = 0; i < pcmData.length / 2; i += frameSize * channels) {
            // 将字节数据转换为short
            for (int j = 0; j < frameSize * channels && (i + j) < pcmData.length / 2; j++) {
                int byteIndex = (i + j) * 2;
                if (byteIndex + 1 < pcmData.length) {
                    shortBuffer[j] = (short) ((pcmData[byteIndex] & 0xFF) | (pcmData[byteIndex + 1] << 8));
                }
            }

            // 编码
            byte[] opusBuffer = new byte[1275]; // 最大Opus帧大小
            int opusLength = encoder.encode(shortBuffer, 0, frameSize, opusBuffer, 0, opusBuffer.length);

            // 创建正确大小的帧并添加到列表
            byte[] opusFrame = new byte[opusLength];
            System.arraycopy(opusBuffer, 0, opusFrame, 0, opusLength);
            opusFrames.add(opusFrame);
        }

        return opusFrames;
    }

    /**
     * 清理会话资源
     */
    public void cleanupSession(String sessionId) {
        sessionDecoders.remove(sessionId);
    }
}