package com.nip.core.utils.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sm3Util测试类
 */
public class Sm3UtilTest {

    @Test
    public void testSM3() {
        String originalText = "Hello, SM3!";
        String hash = Sm3Util.digest(originalText);

        assertNotNull(hash);
        assertEquals(64, hash.length()); // SM3 输出 256 位 = 64 个十六进制字符

        // 验证相同输入产生相同输出
        String hash2 = Sm3Util.digest(originalText);
        assertEquals(hash, hash2);

        System.out.println("SM3 Test - Original: " + originalText);
        System.out.println("SM3 Test - Hash: " + hash);
    }

    @Test
    public void testSM3HMAC() {
        String originalText = "Hello, SM3 HMAC!";
        String keyHex = "0123456789ABCDEF0123456789ABCDEF";

        String hmac = Sm3Util.hmac(originalText, keyHex);
        assertNotNull(hmac);
        assertEquals(64, hmac.length());

        System.out.println("SM3 HMAC Test - Original: " + originalText);
        System.out.println("SM3 HMAC Test - Key: " + keyHex);
        System.out.println("SM3 HMAC Test - HMAC: " + hmac);
    }
}