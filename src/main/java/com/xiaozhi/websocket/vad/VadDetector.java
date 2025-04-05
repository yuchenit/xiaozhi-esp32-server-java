package com.xiaozhi.websocket.vad;

/**
 * 语音活动检测器接口
 */
public interface VadDetector {
  /**
   * 初始化会话
   */
  void initializeSession(String sessionId);

  /**
   * 处理音频数据
   * 
   * @param sessionId 会话ID
   * @param pcmData   PCM格式的音频数据
   * @return 如果检测到语音结束，返回完整的音频数据；否则返回null
   */
  byte[] processAudio(String sessionId, byte[] pcmData);

  /**
   * 设置VAD阈值
   */
  void setThreshold(float threshold);

  /**
   * 重置会话状态
   * 
   * @param sessionId 会话ID
   */
  void resetSession(String sessionId);

  /**
   * 检查当前是否正在说话
   * 
   * @param sessionId 会话ID
   * @return 如果当前正在说话返回true，否则返回false
   */
  boolean isSpeaking(String sessionId);

  /**
   * 获取当前语音概率
   * 
   * @param sessionId 会话ID
   * @return 当前语音概率值，范围0.0-1.0
   */
  float getCurrentSpeechProbability(String sessionId);
}