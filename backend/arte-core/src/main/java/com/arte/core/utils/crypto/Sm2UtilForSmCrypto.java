package com.arte.core.utils.crypto;

import com.arte.core.i18n.MessageUtils;

import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SM2 加密解密工具类
 * 专为前端 sm-crypto 设计，支持互相加密解密
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/3/18
 */
public class Sm2UtilForSmCrypto extends Sm2Util {

    /**
     * 转换前端密文格式（前端->后端）
     */
    private static byte[] convertFromFrontendFormat(String cipherTextHex) {
        byte[] cipherBytes = Hex.decode(cipherTextHex);
        if (cipherBytes.length >= 96 && cipherBytes[0] != 0x04) {
            // 前端格式：C1（64 字节）+ C3（32 字节）+ C2（变长）
            // 转换为 BouncyCastle 格式：04 + C1（64 字节）+ C3（32 字节）+ C2（变长）
            byte[] convertedBytes = new byte[cipherBytes.length + 1];
            convertedBytes[0] = 0x04;
            System.arraycopy(cipherBytes, 0, convertedBytes, 1, cipherBytes.length);
            return convertedBytes;
        }

        return cipherBytes;
    }

    /**
     * 转换为前端密文格式（后端->前端）
     */
    private static String convertToFrontendFormat(byte[] cipherBytes) {
        // BouncyCastle 输出格式：04 + C1（64 字节）+ C3（32 字节）+ C2（变长）
        // 转换为前端格式：C1（64 字节）+ C3（32 字节）+ C2（变长）
        if (cipherBytes.length > 0 && cipherBytes[0] == 0x04) {
            // 移除 04 前缀
            byte[] frontendFormat = Arrays.copyOfRange(cipherBytes, 1, cipherBytes.length);
            return Hex.toHexString(frontendFormat).toLowerCase();
        }
        return Hex.toHexString(cipherBytes).toLowerCase();
    }

    /**
     * SM2 加密 - 输出前端兼容格式
     *
     * @param plainText    明文
     * @param publicKeyHex 公钥（16 进制字符串）
     * @return 前端兼容的密文（16 进制字符串）
     */
    public static String encryptForSmCrypto(String plainText, String publicKeyHex) {
        ECPublicKeyParameters publicKeyParams = createPublicKey(publicKeyHex);
        return encryptForSmCrypto(plainText, publicKeyParams);
    }

    public static String encryptForSmCrypto(String plainText, ECPublicKeyParameters publicKey) {
        try {
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(true, new ParametersWithRandom(publicKey, new SecureRandom()));
            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] cipherBytes = engine.processBlock(plainBytes, 0, plainBytes.length);
            // 转换为前端兼容格式
            return convertToFrontendFormat(cipherBytes);
        } catch (Exception e) {
            throw new RuntimeException(MessageUtils.get("error.crypto.sm2EncryptFailedWith", e.getMessage()), e);
        }
    }

    /**
     * SM2解密 - 兼容前端密文
     *
     * @param cipherTextHex 密文（16 进制字符串）
     * @param privateKeyHex 私钥（16 进制字符串）
     * @return 明文
     */
    public static String decryptForSmCrypto(String cipherTextHex, String privateKeyHex) {
        ECPrivateKeyParameters privateKeyParams = createPrivateKey(privateKeyHex);
        return decryptForSmCrypto(cipherTextHex, privateKeyParams);
    }

    public static String decryptForSmCrypto(String cipherTextHex, ECPrivateKeyParameters privateKey) {
        try {
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(false, privateKey);
            // 转换前端密文格式
            byte[] cipherBytes = convertFromFrontendFormat(cipherTextHex.toLowerCase().trim());
            byte[] plainBytes = engine.processBlock(cipherBytes, 0, cipherBytes.length);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(MessageUtils.get("error.crypto.sm2DecryptFailedWith", e.getMessage()), e);
        }
    }
}