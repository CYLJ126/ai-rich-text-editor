package com.arte.core.utils.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import java.security.SecureRandom;
import java.security.Security;

/**
 * 国密算法工具抽象类
 *
 * @author CYLJ126
 * @version 1.0
 */
public abstract class AbstractGmUtil {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    protected static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        return Hex.toHexString(bytes).toUpperCase();
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        return Hex.decode(hex);
    }

    /**
     * 生成随机字节数组
     */
    public static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * 生成随机十六进制字符串
     */
    public static String generateRandomHex(int byteLength) {
        return bytesToHex(generateRandomBytes(byteLength));
    }

    /**
     * 安全比较两个字节数组
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}