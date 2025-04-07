package com.xiaozhi.websocket.token;

import com.xiaozhi.entity.SysConfig;

/**
 * Token管理器接口
 * 定义通用的Token管理功能
 */
public interface TokenManager {
    /**
     * 获取指定配置的有效Token
     * @param config 配置
     * @return Token字符串，如果无效则返回null
     */
    String getValidToken(SysConfig config);
    
    /**
     * 刷新指定配置的Token
     * @param config 配置
     * @return 刷新后的Token字符串
     */
    String refreshToken(SysConfig config);
    
    /**
     * 检查Token是否即将过期
     * @param configId 配置ID
     * @return 如果Token即将过期返回true，否则返回false
     */
    boolean isTokenExpiringSoon(Integer configId);
    
    /**
     * 初始化所有需要的Token
     */
    void initializeAllTokens();
}