package com.arte.core.utils.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sm4Util测试类
 */
public class Sm4UtilTest {

    @Test
    public void testSM4ECB() throws Exception {
        // 测试密钥生成
        String key = Sm4Util.generateKeyHex();
        assertNotNull(key);
        assertEquals(32, key.length()); // 16 字节 = 32 个十六进制字符

        // 测试加密解密
        String originalText = "Hello, SM4!";
        String encrypted = Sm4Util.encrypt(originalText, key);
        String decrypted = Sm4Util.decrypt(encrypted, key);

        assertEquals(originalText, decrypted);
        System.out.println("SM4 Test - Key: " + key);
        System.out.println("SM4 Test - Original: " + originalText);
        System.out.println("SM4 Test - Encrypted: " + encrypted);
        System.out.println("SM4 Test - Decrypted: " + decrypted);
    }

    @Test
    public void testSM4CBC() throws Exception {
        byte[] key = Sm4Util.generateKey();
        System.out.println("SM4 CBC Test - Key: " + Sm4Util.bytesToHex(key));
        byte[] iv = Sm4Util.generateIV();
        System.out.println("SM4 CBC Test - IV: " + Sm4Util.bytesToHex(iv));
        String originalText = "Hello, SM4 CBC Mode!";

        byte[] encrypted = Sm4Util.encryptCBC(
                originalText.getBytes(StandardCharsets.UTF_8), key, iv);
        System.out.println("SM4 CBC Test - Encrypted: " + Sm4Util.bytesToHex(encrypted));
        byte[] decrypted = Sm4Util.decryptCBC(encrypted, key, iv);

        String result = new String(decrypted, StandardCharsets.UTF_8);
        assertEquals(originalText, result);

        System.out.println("SM4 CBC Test - Original: " + originalText);
        System.out.println("SM4 CBC Test - Result: " + result);
    }
}