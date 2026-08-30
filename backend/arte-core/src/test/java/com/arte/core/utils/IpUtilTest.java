package com.arte.core.utils;

import org.junit.Test;

import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;

public class IpUtilTest {

    @Test
    public void ipV4ToLong() {
//        String ip = "255.255.255.255";
        String ip = "192.168.0.1";
        long ipLong = IpUtil.ipV4ToLong(ip);
        System.out.println("转换为 Long：" + ipLong);
        String ipStr = IpUtil.ipV4ToString(ipLong);
        System.out.println("转换为 String：" + ipStr);
        assertEquals(ip, ipStr);
    }

    @Test
    public void ipV6ToBytes() throws UnknownHostException {
        String ipv6 = "2001:db8:85a3:0:0:8a2e:370:7334";
//        String ipv6 = "0:0:0:0:0:0:0:1";
//        String ipv6 = "192.168.0.1";
        byte[] ipv6Bytes = IpUtil.ipV6ToBytes(ipv6);
        System.out.println("转换为字节数组：" + ipv6Bytes);
        String ipv6Str = IpUtil.ipV6ToString(ipv6Bytes);
        System.out.println("转换为 String：" + ipv6Str);
        assertEquals(ipv6, ipv6Str);
    }
}