package com.nip.core.utils.crypto;

import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;

/**
 * SM3 算法工具类
 *
 * @author zhangsc
 * @since 2026/3/20 10:35
 */
public class Sm3Util extends AbstractGmUtil {
    /**
     * SM3 摘要计算
     */
    public static byte[] digest(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    /**
     * SM3 摘要计算（字符串）
     */
    public static String digest(String data) {
        byte[] hash = digest(data.getBytes(StandardCharsets.UTF_8));
        return Hex.toHexString(hash).toUpperCase();
    }

    /**
     * SM3 摘要计算（多次更新）
     */
    public static String digest(String... dataArray) {
        SM3Digest digest = new SM3Digest();
        for (String data : dataArray) {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            digest.update(bytes, 0, bytes.length);
        }
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return Hex.toHexString(result).toUpperCase();
    }

    /**
     * SM3 HMAC
     */
    public static byte[] hmac(byte[] data, byte[] key) {
        int blockSize = 64; // SM3 块大小

        // 如果密钥长度大于块大小，先进行哈希
        if (key.length > blockSize) {
            key = digest(key);
        }

        // 填充密钥到块大小
        byte[] paddedKey = new byte[blockSize];
        System.arraycopy(key, 0, paddedKey, 0, key.length);

        // 计算内部和外部填充
        byte[] innerPad = new byte[blockSize];
        byte[] outerPad = new byte[blockSize];

        for (int i = 0; i < blockSize; i++) {
            innerPad[i] = (byte) (paddedKey[i] ^ 0x36);
            outerPad[i] = (byte) (paddedKey[i] ^ 0x5C);
        }

        // 计算内部哈希
        SM3Digest innerDigest = new SM3Digest();
        innerDigest.update(innerPad, 0, innerPad.length);
        innerDigest.update(data, 0, data.length);
        byte[] innerHash = new byte[innerDigest.getDigestSize()];
        innerDigest.doFinal(innerHash, 0);

        // 计算外部哈希
        SM3Digest outerDigest = new SM3Digest();
        outerDigest.update(outerPad, 0, outerPad.length);
        outerDigest.update(innerHash, 0, innerHash.length);
        byte[] result = new byte[outerDigest.getDigestSize()];
        outerDigest.doFinal(result, 0);

        return result;
    }

    /**
     * SM3 HMAC（字符串）
     */
    public static String hmac(String data, String keyHex) {
        byte[] key = Hex.decode(keyHex);
        byte[] result = hmac(data.getBytes(StandardCharsets.UTF_8), key);
        return Hex.toHexString(result).toUpperCase();
    }

    /**
     * 验证摘要
     */
    public static boolean verify(String data, String expectedHash) {
        String actualHash = digest(data);
        return actualHash.equalsIgnoreCase(expectedHash);
    }

    /**
     * 验证 HMAC
     */
    public static boolean verifyHmac(String data, String keyHex, String expectedHmac) {
        String actualHmac = hmac(data, keyHex);
        return actualHmac.equalsIgnoreCase(expectedHmac);
    }
}
