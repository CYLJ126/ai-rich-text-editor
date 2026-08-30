package com.arte.core.utils.crypto;

import org.junit.jupiter.api.Test;

class Sm2UtilForSmCryptoTest {

    @Test
    public void testEncrypt() {
        // 使用从私钥推导的公钥，确保密钥对匹配
        String privateKey = "266fcc0cd2495bbabd00d602bb6ade55d0cbe0ea587cc00025a9264516a34809";
        String publicKey = Sm2UtilForSmCrypto.derivePublicKey(privateKey);
        String plainText = "cwycwy123";
        String frontendCipherText = "6e5b9790d9e64dd0638e54fa1078107634a32336f927cdf658efca6a9c4c984b95a007f59bcbd572b430fb0e7a63e9f70a034f910df54f3a0c0da80e78aea1e084ce065ba80aff3e28687652c5fbc0c6b0e21120d92b7b5b88051ba5f11aee5a004f31a16c1cf1997c60fcf062da76";
        System.out.println("=== SM2 前后端兼容性测试 ===");
        System.out.println("私钥: " + privateKey);
        System.out.println("公钥: " + publicKey);
        System.out.println("原文: " + plainText);
        System.out.println();
        // 测试1: 解密前端密文
        System.out.println("测试1: 后端解密前端密文");
        try {
            String decrypted = Sm2UtilForSmCrypto.decryptForSmCrypto(frontendCipherText, privateKey);
            System.out.println("✓ 解密成功: " + decrypted);
            System.out.println("✓ 内容正确: " + plainText.equals(decrypted));
        } catch (Exception e) {
            System.err.println("✗ 解密失败: " + e.getMessage());
        }
        System.out.println();
        // 测试2: 后端加密，准备给前端解密
        System.out.println("测试2: 后端加密（给前端解密）");
        String backendEncrypted = Sm2UtilForSmCrypto.encryptForSmCrypto(plainText, publicKey);
        System.out.println("✓ 后端加密成功");
        System.out.println("后端生成的密文: " + backendEncrypted);
        System.out.println("密文长度: " + backendEncrypted.length() + " 字符");

        // 验证后端能否解密自己的密文
        String selfDecrypted = Sm2UtilForSmCrypto.decryptForSmCrypto(backendEncrypted, privateKey);
        System.out.println("✓ 后端自验证: " + plainText.equals(selfDecrypted));

        System.out.println();
        System.out.println("=== 前端测试代码 ===");
        System.out.println("请在前端使用以下代码测试:");
        System.out.println("const privateKey = '" + privateKey + "';");
        System.out.println("const cipherText = '" + backendEncrypted + "';");
        System.out.println("const decrypted = sm2.doDecrypt(cipherText, privateKey, 1);");
        System.out.println("console.log('前端解密结果:', decrypted);");

        System.out.println();
        // 测试3: 多次加密测试（因为SM2加密有随机性）
        System.out.println("测试3: 多次加密测试（验证随机性）");
        for (int i = 1; i <= 3; i++) {
            try {
                String encrypted = Sm2UtilForSmCrypto.encryptForSmCrypto(plainText, publicKey);
                String decrypted = Sm2UtilForSmCrypto.decryptForSmCrypto(encrypted, privateKey);
                System.out.println("第" + i + "次 - 密文: " + encrypted.substring(0, 32) + "...");
                System.out.println("第" + i + "次 - 解密: " + (plainText.equals(decrypted) ? "✓成功" : "✗失败"));
            } catch (Exception e) {
                System.err.println("第" + i + "次 - ✗失败: " + e.getMessage());
            }
        }
    }

}