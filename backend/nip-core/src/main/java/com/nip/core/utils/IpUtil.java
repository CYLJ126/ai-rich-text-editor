package com.nip.core.utils;

import cn.hutool.core.text.CharSequenceUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.*;
import java.util.Enumeration;

/**
 * @author zhangsc
 * @since 2024/7/11 11:49
 */
@Slf4j
public class IpUtil {

    private static final String UNKNOWN_IP = "unknown";

    private IpUtil() {
    }

    /**
     * 获取本机 IP 地址
     *
     * @return 本机 IP
     */
    public static String getHostIp() {
        Enumeration<NetworkInterface> allNetInterfaces;
        try {
            allNetInterfaces = NetworkInterface.getNetworkInterfaces();
            while (allNetInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = allNetInterfaces.nextElement();
                Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress ip = addresses.nextElement();
                    if (ip instanceof Inet4Address
                            && !ip.isLoopbackAddress()
                            && !ip.getHostAddress().contains(":")) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.error("获取本地IP异常", e);
        }
        return null;
    }

    /**
     * 获取请求调用方实际 IP
     *
     * @param request http请求
     * @return 请求调用方实际 IP
     */
    public static String getClientIpAddr(HttpServletRequest request) {
        String ip = request.getHeader("x-real-ip");
        if (CharSequenceUtil.isBlank(ip) || UNKNOWN_IP.equalsIgnoreCase(ip)) {
            ip = request.getHeader("x-forwarded-for");
        }
        if (CharSequenceUtil.isBlank(ip) || UNKNOWN_IP.equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (CharSequenceUtil.isBlank(ip) || UNKNOWN_IP.equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (CharSequenceUtil.isBlank(ip) || UNKNOWN_IP.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * ipv4 转 long，数据库用 INT UNSIGNED 存，占 4 字节，省空间，查询的时候用{@link #ipV4ToString(long)}转回字符串展示
     * <p>
     * ipv6 不支持，IPv6 是 128 位的，超出 long 能表示的范围，得用二进制
     * <p>
     * mysql 中转换函数：
     * -- 存的时候
     * select INET_ATON('192.168.0.1');
     * -- 查的时候
     * SELECT INET_NTOA(3232235521) ;
     *
     * @param ip ipv4 地址
     * @return ipv4 地址对应的 long 类型数值
     */
    public static long ipV4ToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < parts.length; i++) {
            result |= (Long.parseLong(parts[i]) << (8 * (3 - i)));
        }
        return result;
    }

    /**
     * long 转 ipv4 地址字符串，与{@link #ipV4ToLong(String)}互转
     *
     * @param ip ipv4 地址对应的 long 类型数值
     * @return ipv4 地址字符串
     */
    public static String ipV4ToString(long ip) {
        return ((ip >> 24) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                (ip & 0xFF);
    }

    /**
     * ipv6 转 byte[]，数据库用 BINARY(16) 存，占 16 字节，省空间，查询的时候用{@link #ipV6ToString(byte[])}转回字符串展示
     * 兼容 ipv4 地址，IPv4地址会被转换为IPv6地址
     * <p>
     * mysql 中对应字段类型：VARBINARY(16) 或 BINARY(16)
     * 如果系统既有 IPv4 又有 IPv6，可以统一用 VARBINARY(16) 存。 IPv4 的数据只用前 4 个字节，剩下补 0 即可，这样兼容性最好。
     * <p>
     * 转换函数：
     * -- 存和查的时候（存时加 INET6_ATON 函数，查时加 INET6_NTOA 函数）
     * SELECT
     * INET6_ATON('2001:0db8:85a3:0000:0000:8a2e:0370:7334') AS binary_format,
     * INET6_NTOA(INET6_ATON('2001:0db8:85a3:0000:0000:8a2e:0370:7334')) AS original_ip;
     *
     * @param ipv6 ipv6 地址
     * @return ipv6 地址对应的 byte[] 类型数值
     * @throws UnknownHostException ipv6 地址转换异常
     */
    public static byte[] ipV6ToBytes(String ipv6) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ipv6);
        return address.getAddress();
    }

    public static String ipV6ToString(byte[] ipv6Bytes) throws UnknownHostException {
        return InetAddress.getByAddress(ipv6Bytes).getHostAddress();
    }
}
