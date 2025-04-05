package com.xiaozhi.websocket.handler;

import cn.hutool.extra.spring.SpringUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysDeviceService;
import com.xiaozhi.websocket.llm.LlmManager;
import com.xiaozhi.websocket.service.AudioService;
import com.xiaozhi.websocket.service.MessageService;
import com.xiaozhi.websocket.service.TextToSpeechService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.DEVICE_ID;
import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.SESSION_ID;

/**
 * WebSocket 文本消息处理器
 * 处理所有文本类型的 WebSocket 消息
 */
@Component
public class TextWebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

  private static final Logger logger = LoggerFactory.getLogger(TextWebSocketFrameHandler.class);

  private final SysDeviceService deviceService = SpringUtil.getBean(SysDeviceService.class);

  private final AudioService audioService = SpringUtil.getBean(AudioService.class);

  private final LlmManager llmManager = SpringUtil.getBean(LlmManager.class);

  private final MessageService messageService = SpringUtil.getBean(MessageService.class);

  private final TextToSpeechService textToSpeechService = SpringUtil.getBean(TextToSpeechService.class);

  private final ObjectMapper objectMapper = new ObjectMapper();

  // 用于存储所有连接的会话
  public static final ConcurrentHashMap<String, SysDevice> DEVICES_CONFIG = new ConcurrentHashMap<>();

  // 用于跟踪会话是否处于监听状态
  public static final Map<String, Boolean> LISTENING_STATE = new ConcurrentHashMap<>();

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    logger.info("客户端连接: {}", ctx.channel().remoteAddress());
    super.channelActive(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    String sessionId = ctx.channel().attr(SESSION_ID).get();
    SysDevice device = DEVICES_CONFIG.get(sessionId);
    // 更新设备在线时间
    if (device != null) {
      deviceService
          .update(new SysDevice().setDeviceId(device.getDeviceId()).setState("0")
              .setLastLogin(new Date().toString()));

      logger.info("WebSocket连接关闭 - SessionId: {}, DeviceId: {}", sessionId, device.getDeviceId());
    }
    LISTENING_STATE.remove(sessionId);
    DEVICES_CONFIG.remove(sessionId);
    logger.info("客户端断开连接: {}", ctx.channel().remoteAddress());
    super.channelInactive(ctx);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
    try {
      // 初始化设备信息
      initializeDeviceSession(ctx);
      // 解析JSON消息
      String payload = frame.text();
      logger.info("客户端发送消息：msg={}",payload);
      JsonNode jsonNode = objectMapper.readTree(payload);
      String messageType = jsonNode.path("type").asText();

      switch (messageType) {
        case "hello":
          handleHelloMessage(ctx, jsonNode);
          break;
        case "listen":
          handleListenMessage(ctx, jsonNode);
          break;
        case "abort":
          handleAbortMessage(ctx, jsonNode);
          break;
        case "iot":
          handleIotMessage(ctx, jsonNode);
          break;
        default:
          logger.warn("未知的消息类型: {}", messageType);
          break;
      }
    } catch (Exception e) {
      logger.error("处理消息失败", e);
    }
  }

  /**
   * 初始化设备会话
   */
  private void initializeDeviceSession(ChannelHandlerContext ctx) {
    String sessionId = ctx.channel().attr(SESSION_ID).get();
    String deviceId = ctx.channel().attr(DEVICE_ID).get();
    List<SysDevice> devices = deviceService.query(new SysDevice().setDeviceId(deviceId));
    SysDevice device;
    if (devices.isEmpty()) {
      device = new SysDevice();
      device.setDeviceId(deviceId);
      device.setSessionId(sessionId);
      handleUnboundDevice(ctx, device);
    } else {
      device = devices.get(0);
      device.setSessionId(sessionId);
      deviceService
              .update(new SysDevice().setDeviceId(device.getDeviceId()).setState("1")
                      .setLastLogin(new Date().toString()));
    }
    DEVICES_CONFIG.put(sessionId, device);
    LISTENING_STATE.put(sessionId, false);

    logger.info("WebSocket连接初始化 - SessionId: {}, DeviceId: {}", sessionId, deviceId);
  }

  /**
   * 处理未绑定的设备
   */
  private void handleUnboundDevice(ChannelHandlerContext ctx, SysDevice device) {
    try {
      SysDevice codeResult = deviceService.generateCode(device);
      String audioFilePath;
      if (!StringUtils.hasText(codeResult.getAudioPath())) {
        audioFilePath = textToSpeechService.textToSpeech("请到设备管理页面添加设备，输入验证码" + codeResult.getCode());
        codeResult.setDeviceId(device.getDeviceId());
        codeResult.setSessionId(device.getSessionId());
        codeResult.setAudioPath(audioFilePath);
        deviceService.updateCode(codeResult);
      } else {
        audioFilePath = codeResult.getAudioPath();
      }
      logger.info("设备未绑定，返回验证码");
      audioService.sendAudio(ctx.channel(), audioFilePath, codeResult.getCode());
    }catch (Exception e){
      logger.error("handleUnboundDevice error:",e);
    }
  }

  /**
   * 处理hello消息
   */
  private void handleHelloMessage(ChannelHandlerContext ctx, JsonNode jsonNode) {
    String sessionId = ctx.channel().attr(SESSION_ID).get();
    logger.info("收到hello消息 - SessionId: {}, JsonNode={}", sessionId,jsonNode);

    // 验证客户端hello消息
 /*   if (!jsonNode.path("transport").asText().equals("websocket")) {
      logger.warn("不支持的传输方式: {}", jsonNode.path("transport").asText());
      ctx.close();
      return;
    }*/

    // 解析音频参数
    JsonNode audioParams = jsonNode.path("audio_params");
    String format = audioParams.path("format").asText();
    int sampleRate = audioParams.path("sample_rate").asInt();
    int channels = audioParams.path("channels").asInt();
    int frameDuration = audioParams.path("frame_duration").asInt();

    logger.info("客户端音频参数 - 格式: {}, 采样率: {}, 声道: {}, 帧时长: {}ms",
        format, sampleRate, channels, frameDuration);

    // 回复hello消息
    ObjectNode response = objectMapper.createObjectNode();
    response.put("type", "hello");
    response.put("transport", "websocket");
    response.put("session_id", sessionId);

    // 添加音频参数（可以根据服务器配置调整）
    ObjectNode responseAudioParams = response.putObject("audio_params");
    responseAudioParams.put("format", format);
    responseAudioParams.put("sample_rate", sampleRate);
    responseAudioParams.put("channels", channels);
    responseAudioParams.put("frame_duration", frameDuration);

    ctx.writeAndFlush(new TextWebSocketFrame(response.toString()));
  }

  /**
   * 处理listen消息
   */
  private void handleListenMessage(ChannelHandlerContext ctx, JsonNode jsonNode) {
    String sessionId = ctx.channel().attr(SESSION_ID).get();

    // 解析listen消息中的state和mode字段
    String state = jsonNode.path("state").asText();
    String mode = jsonNode.path("mode").asText();

    logger.info("收到listen消息 - SessionId: {}, State: {}, Mode: {}", sessionId, state, mode);
    // 根据state处理不同的监听状态
    switch (state) {
      case "start":
        // 开始监听，准备接收音频数据
        logger.info("开始监听 - Mode: {}", mode);
        LISTENING_STATE.put(sessionId, true);
        break;
      case "stop":
        // 停止监听
        logger.info("停止监听");
        LISTENING_STATE.put(sessionId, false);
        break;
      case "detect":
        // 检测到唤醒词
        String text = jsonNode.path("text").asText();
        logger.info("检测到唤醒词: {}", text);
        // 处理唤醒词逻辑
        processWakeWord(ctx, sessionId, text);
        break;
      default:
        logger.warn("未知的listen状态: {}", state);
        break;
    }
  }

  /**
   * 处理唤醒词
   */
  private void processWakeWord(ChannelHandlerContext ctx, String sessionId, String text) {
    SysDevice device = DEVICES_CONFIG.get(sessionId);
    // 设置为非监听状态，防止处理自己的声音
    LISTENING_STATE.put(sessionId, false);
    messageService.sendMessage(ctx.channel(), "stt", "start", text);

    // 使用句子切分处理流式响应
    llmManager.chatStreamBySentence(device, text, (sentence, isStart, isEnd) -> {
      try {
        String audioPath = textToSpeechService.textToSpeech(sentence, null, device.getVoiceName());
        audioService.sendAudio(ctx.channel(), audioPath, sentence, isStart, isEnd);
      } catch (Exception e) {
        logger.error("处理句子失败: {}", e.getMessage(), e);
      }
    });
  }

  /**
   * 处理abort消息
   */
  private void handleAbortMessage(ChannelHandlerContext ctx, JsonNode jsonNode) {
    String sessionId = ctx.channel().attr(SESSION_ID).get();
    String reason = jsonNode.path("reason").asText();

    logger.info("收到abort消息 - SessionId: {}, Reason: {}", sessionId, reason);

    // 终止语音发送
    audioService.sendStop(ctx.channel());
  }

  /**
   * 处理IoT消息
   */
  private void handleIotMessage(ChannelHandlerContext ctx, JsonNode jsonNode) {
    String sessionId = ctx.channel().attr(SESSION_ID).get();
    logger.info("收到IoT消息 - SessionId: {}", sessionId);

    // 处理设备描述信息
    if (jsonNode.has("descriptors")) {
      JsonNode descriptors = jsonNode.path("descriptors");
      logger.info("收到设备描述信息: {}", descriptors);
      // 处理设备描述信息的逻辑
    }

    // 处理设备状态更新
    if (jsonNode.has("states")) {
      JsonNode states = jsonNode.path("states");
      logger.info("收到设备状态更新: {}", states);
      // 处理设备状态更新的逻辑
    }
  }

  /**
   * 获取会话的监听状态
   */
  public static boolean isListening(String sessionId) {
    return LISTENING_STATE.getOrDefault(sessionId, false);
  }

  /**
   * 获取设备配置
   */
  public static SysDevice getDeviceConfig(String sessionId) {
    return DEVICES_CONFIG.get(sessionId);
  }
}
