package com.nip.core.utils.crypto;

import org.bouncycastle.crypto.engines.SM2Engine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sm2Util 测试类
 */
public class Sm2UtilTest {

    @Test
    public void testSM21() {
        // 系统默认、公用的密钥对
        String privateKey = "266fcc0cd2495bbabd00d602bb6ade55d0cbe0ea587cc00025a9264516a34809";
        String publicKey = "04a47da566fc10ac4458db04457226097c4c262a86b2325d01dfe7637c8e580af59b56e015756095d370e49d5ddab10c0c838ae1129555a70f4f0f10ca63b9e8d1";
        String key = "sk-4526Test";
        System.out.println(Sm2Util.encryptHex(publicKey, key.getBytes(), SM2Engine.Mode.C1C2C3));
    }

    @Test
    public void testSM2() {
        // 生成密钥对
        Sm2Util.SM2KeyPair keyPair = Sm2Util.generateKeyPair();
        String publicKey = keyPair.getPublicKeyHex();
        String privateKey = keyPair.getPrivateKeyHex();

        System.out.println("Public Key (130 chars): " + publicKey);
        System.out.println("Private Key (64 chars): " + privateKey);
        System.out.println("Public Key Length: " + publicKey.length());
        System.out.println("Private Key Length: " + privateKey.length());
        assert Sm2Util.validateKeyPair(publicKey, privateKey);

        // 验证格式
        Assertions.assertTrue(publicKey.startsWith("04"));
        assertEquals(130, publicKey.length());
        assertEquals(64, privateKey.length());

        // 测试C1C2C3格式（sm-crypto默认）
        String originalText = "Hello, SM2!";
        String encrypted = Sm2Util.encryptHex(publicKey, originalText.getBytes(), SM2Engine.Mode.C1C2C3);
        String decrypted = new String(Sm2Util.decryptHex(privateKey, encrypted, SM2Engine.Mode.C1C2C3));
        System.out.println("Encrypted Text: " + encrypted);
        System.out.println("Decrypted Text: " + decrypted);

        assertEquals(originalText, decrypted);
        System.out.println("Original: " + originalText);
        System.out.println("Encrypted (C1C2C3): " + encrypted);
        System.out.println("Decrypted: " + decrypted);

        // 测试C1C3C2格式
        String encrypted2 = Sm2Util.encryptHex(publicKey, originalText.getBytes(), SM2Engine.Mode.C1C3C2);
        String decrypted2 = new String(Sm2Util.decryptHex(privateKey, encrypted2, SM2Engine.Mode.C1C3C2));

        assertEquals(originalText, decrypted2);
        System.out.println("Encrypted (C1C3C2): " + encrypted2);
    }

}