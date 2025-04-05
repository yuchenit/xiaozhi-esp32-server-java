package com.xiaozhi.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageInfo;
import com.xiaozhi.common.web.AjaxResult;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.entity.SysUser;
import com.xiaozhi.service.SysDeviceService;
import com.xiaozhi.utils.CmsUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 设备管理
 * 
 * @author Joey
 * 
 */

@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private static final Logger log = LoggerFactory.getLogger(DeviceController.class);

    @Resource
    private SysDeviceService deviceService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 设备查询
     * 
     * @param device
     * @return deviceList
     */
    @GetMapping("/query")
    public Mono<AjaxResult> query(SysDevice device, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            try {
                // 从请求属性中获取用户信息
                SysUser user = exchange.getAttribute(CmsUtils.USER_ATTRIBUTE_KEY);
                if (user != null) {
                    device.setUserId(user.getUserId());
                }
                
                List<SysDevice> deviceList = deviceService.query(device);
                AjaxResult result = AjaxResult.success();
                result.put("data", new PageInfo<>(deviceList));
                return result;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                return AjaxResult.error();
            }
        });
    }

    /**
     * 设备信息更新
     * 
     * @param device
     * @return
     */
    @PostMapping("/update")
    public Mono<AjaxResult> update(SysDevice device, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            try {
                deviceService.update(device);
                return AjaxResult.success();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                return AjaxResult.error();
            }
        });
    }

    /**
     * 添加设备
     * 
     * @param code
     */
    @PostMapping("/add")
    public Mono<AjaxResult> add(String code, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            try {
                SysDevice device = new SysDevice();
                device.setCode(code);
                SysDevice query = deviceService.queryVerifyCode(device);
                if (query == null) {
                    return AjaxResult.error("无效验证码");
                }
                
                // 从请求属性中获取用户信息
                SysUser user = exchange.getAttribute(CmsUtils.USER_ATTRIBUTE_KEY);
                if (user != null) {
                    device.setUserId(user.getUserId());
                }
                
                device.setDeviceId(query.getDeviceId());
                device.setDeviceName("小智");
                deviceService.add(device);
                return AjaxResult.success();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                return AjaxResult.error();
            }
        });
    }

    @PostMapping("/ota")
    public Mono<Void> ota(ServerWebExchange exchange) {
        // 获取device-Id请求头
        String deviceId = exchange.getRequest().getHeaders().getFirst("device-Id");
        SysDevice device = new SysDevice();
        if (deviceId != null) {
            device.setDeviceId(deviceId);
        }
        
        // 读取请求体内容
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        
                        String requestBody = new String(bytes);
                        
                        // 解析JSON请求体
                        if (exchange.getRequest().getHeaders().getContentType() != null && 
                            exchange.getRequest().getHeaders().getContentType().toString().contains("application/json")) {
                            
                            Map<String, Object> jsonData = objectMapper.readValue(requestBody, 
                                                        new TypeReference<Map<String, Object>>() {});

                            // 提取chip_model_name
                            if (jsonData.containsKey("chip_model_name")) {
                                device.setChipModelName((String) jsonData.get("chip_model_name"));
                            }

                            // 提取application.version
                            if (jsonData.containsKey("application") && jsonData.get("application") instanceof Map) {
                                Map<String, Object> application = (Map<String, Object>) jsonData.get("application");
                                if (application.containsKey("version")) {
                                    device.setVersion((String) application.get("version"));
                                }
                            }

                            // 提取board中的ssid和ip
                            if (jsonData.containsKey("board") && jsonData.get("board") instanceof Map) {
                                Map<String, Object> board = (Map<String, Object>) jsonData.get("board");
                                if (board.containsKey("ssid")) {
                                    device.setWifiName((String) board.get("ssid"));
                                }
                                if (board.containsKey("ip")) {
                                    device.setIp((String) board.get("ip"));
                                }
                            }
                        }

                        device.setState("1");
                        device.setLastLogin(new Date().toString());
                        deviceService.update(device);
                        
                        return Mono.empty();
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                        return Mono.error(e);
                    }
                });
    }
}