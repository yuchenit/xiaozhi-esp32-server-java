package com.xiaozhi.utils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import com.xiaozhi.entity.SysUser;

public class CmsUtils {
    
    public static final String USER_ATTRIBUTE_KEY = "user";
    
    public static SysUser getUser(ServerWebExchange exchange) {
        return exchange.getAttribute(USER_ATTRIBUTE_KEY);
    }

    public static void setUser(ServerWebExchange exchange, SysUser user) {
        exchange.getAttributes().put(USER_ATTRIBUTE_KEY, user);
    }

    public static Integer getUserId(ServerWebExchange exchange) {
        SysUser user = getUser(exchange);
        if (user != null) {
            return user.getUserId();
        } else {
            return null;
        }
    }

    public static String getUsername(ServerWebExchange exchange) {
        SysUser user = getUser(exchange);
        if (user != null) {
            return user.getUsername();
        } else {
            return null;
        }
    }

    public static String getName(ServerWebExchange exchange) {
        SysUser user = getUser(exchange);
        if (user != null) {
            return user.getName();
        } else {
            return null;
        }
    }

    /**
     * 获取访问者IP
     *
     * 在一般情况下使用Request.getRemoteAddr()即可，但是经过nginx等反向代理软件后，这个方法会失效。
     *
     * 本方法先从Header中获取X-Real-IP，如果不存在再从X-Forwarded-For获得第一个IP(用,分割)，
     * 如果还不存在则调用Request.getRemoteAddr()。
     *
     * @param exchange ServerWebExchange
     * @return IP地址
     */
    public static String getIpAddr(ServerWebExchange exchange) {
        String ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (!StringUtils.isBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
            if (ip.contains("../") || ip.contains("..\\")) {
                return "";
            }
            return ip;
        }
        
        ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (!StringUtils.isBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
            // 多次反向代理后会有多个IP值，第一个为真实IP。
            int index = ip.indexOf(',');
            if (index != -1) {
                ip = ip.substring(0, index);
            }
            if (ip.contains("../") || ip.contains("..\\")) {
                return "";
            }
            return ip;
        } else {
            ip = exchange.getRequest().getRemoteAddress().getHostString();
            if (ip.contains("../") || ip.contains("..\\")) {
                return "";
            }
            if (ip.equals("0:0:0:0:0:0:0:1")) {
                ip = "127.0.0.1";
            }
            return ip;
        }
    }

    /**
     * 获取本机局域网IP地址
     *
     * @return 本地IP地址
     */
    public static String getLocalIPAddress() {
        try {
            // 获取所有网络接口
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            String fallbackIP = null; // 用于存储可能的备选 IP（如无线网卡的 IP）
    
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
    
                // 排除回环接口（localhost）和未启用的接口
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
    
                // 遍历网络接口的所有 IP 地址
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
    
                    // 只选择 IPv4 地址，并且排除 localhost
                    if (inetAddress instanceof java.net.Inet4Address && !inetAddress.isLoopbackAddress()) {
                        String ipAddress = inetAddress.getHostAddress();
                        // 检查是否是局域网地址（192.168.x.x 或 10.x.x.x 等）
                        if (isPrivateIP(ipAddress)) {
                            // 如果是有线网卡（名称包含 enp 或 eth），直接返回
                            if (networkInterface.getName().contains("enp") || networkInterface.getName().contains("eth")) {
                                return ipAddress;
                            }
                            // 否则将其作为备选 IP
                            fallbackIP = ipAddress;
                        }
                    }
                }
            }
            // 如果没有找到有线网卡的 IP，返回备选 IP
            return fallbackIP != null ? fallbackIP : "localhost";
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 如果没有找到局域网 IP 地址，返回 localhost
        return "localhost";
    }

    // 检查是否是私有 IP 地址
    public static boolean isPrivateIP(String ipAddress) {
        return ipAddress.startsWith("192.168.") || ipAddress.startsWith("10.") ||
               ipAddress.startsWith("172.16.") || ipAddress.startsWith("172.17.") ||
               ipAddress.startsWith("172.18.") || ipAddress.startsWith("172.19.") ||
               ipAddress.startsWith("172.20.") || ipAddress.startsWith("172.21.") ||
               ipAddress.startsWith("172.22.") || ipAddress.startsWith("172.23.") ||
               ipAddress.startsWith("172.24.") || ipAddress.startsWith("172.25.") ||
               ipAddress.startsWith("172.26.") || ipAddress.startsWith("172.27.") ||
               ipAddress.startsWith("172.28.") || ipAddress.startsWith("172.29.") ||
               ipAddress.startsWith("172.30.") || ipAddress.startsWith("172.31.");
    }
}