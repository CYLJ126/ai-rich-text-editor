package com.arte.core.utils.crypto;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * SM2 算法工具类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/3/20 10:32
 */
@Slf4j
public class Sm2Util extends AbstractGmUtil {

    // SM2 相关常量
    protected static final X9ECParameters SM2_PARAMS = GMNamedCurves.getByName("sm2p256v1");
    protected static final ECDomainParameters SM2_DOMAIN_PARAMS = new ECDomainParameters(
            SM2_PARAMS.getCurve(), SM2_PARAMS.getG(), SM2_PARAMS.getN());

    /**
     * SM2 密钥对
     */
    public record SM2KeyPair(ECPrivateKeyParameters privateKey, ECPublicKeyParameters publicKey) {

        public String getPrivateKeyHex() {
            return String.format("%064x", privateKey.getD()).toUpperCase();
        }

        public String getPublicKeyHex() {
            ECPoint point = publicKey.getQ().normalize();
            String x = String.format("%064x", point.getXCoord().toBigInteger()).toUpperCase();
            String y = String.format("%064x", point.getYCoord().toBigInteger()).toUpperCase();
            return "04" + x + y;
        }
    }

    /**
     * 生成 SM2 密钥对
     */
    public static SM2KeyPair generateKeyPair() {
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(SM2_DOMAIN_PARAMS, RANDOM));
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();
        ECPrivateKeyParameters privateKey = (ECPrivateKeyParameters) keyPair.getPrivate();
        ECPublicKeyParameters publicKey = (ECPublicKeyParameters) keyPair.getPublic();
        return new SM2KeyPair(privateKey, publicKey);
    }

    /**
     * 从十六进制字符串创建私钥
     */
    public static ECPrivateKeyParameters createPrivateKey(String privateKeyHex) {
        BigInteger privateKeyInt = new BigInteger(privateKeyHex.toLowerCase().trim(), 16);
        return new ECPrivateKeyParameters(privateKeyInt, SM2_DOMAIN_PARAMS);
    }

    /**
     * 从十六进制字符串创建公钥
     */
    public static ECPublicKeyParameters createPublicKey(String publicKeyHex) {
        String pubKeyStr = publicKeyHex.toLowerCase().trim();
        if (!pubKeyStr.startsWith("04")) {
            pubKeyStr = "04" + pubKeyStr;
        }
        byte[] pubKeyBytes = Hex.decode(pubKeyStr);
        ECPoint publicKeyPoint = SM2_PARAMS.getCurve().decodePoint(pubKeyBytes);
        return new ECPublicKeyParameters(publicKeyPoint, SM2_DOMAIN_PARAMS);
    }

    /**
     * 从私钥推导公钥
     */
    public static String derivePublicKey(String privateKeyHex) {
        try {
            BigInteger privateKeyInt = new BigInteger(privateKeyHex.toLowerCase().trim(), 16);
            ECPoint publicKeyPoint = SM2_PARAMS.getG().multiply(privateKeyInt);
            byte[] publicKeyBytes = publicKeyPoint.getEncoded(false);
            return Hex.toHexString(publicKeyBytes).toLowerCase();
        } catch (Exception e) {
            throw new RuntimeException("从私钥推导公钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证密钥对是否正确
     */
    public static boolean validateKeyPair(String publicKeyHex, String privateKeyHex) {
        try {
            String testMessage = "test";
            String encrypted = encryptHex(publicKeyHex, testMessage.getBytes(), SM2Engine.Mode.C1C3C2);
            String decrypted = new String(decryptHex(privateKeyHex, encrypted, SM2Engine.Mode.C1C3C2));
            if (!testMessage.equals(decrypted)) {
                return false;
            }
            encrypted = encryptHex(publicKeyHex, testMessage.getBytes(), SM2Engine.Mode.C1C2C3);
            decrypted = new String(decryptHex(privateKeyHex, encrypted, SM2Engine.Mode.C1C2C3));
            return testMessage.equals(decrypted);
        } catch (Exception e) {
            log.error("验证失败：", e);
            return false;
        }
    }

    public static String encryptHex(String publicKeyHex, String data) {
        return encryptHex(publicKeyHex, data.getBytes(), SM2Engine.Mode.C1C2C3);
    }

    public static String encryptHex(String publicKeyHex, String data, SM2Engine.Mode mode) {
        return encryptHex(publicKeyHex, data.getBytes(), mode);
    }

    /**
     * 使用公钥加密
     * @param publicKeyHex 16 进制公钥，格式 04 + X + Y
     * @param data 明文
     * @return 16 进制密文
     */
    public static String encryptHex(String publicKeyHex, byte[] data, SM2Engine.Mode mode) {
        try {
            ECPublicKeyParameters publicKeyParameters = createPublicKey(publicKeyHex);
            SM2Engine engine = new SM2Engine(mode);
            engine.init(true, new ParametersWithRandom(publicKeyParameters, new SecureRandom()));
            byte[] cipher = engine.processBlock(data, 0, data.length);
            return Hex.toHexString(cipher);
        } catch (InvalidCipherTextException e) {
            throw new RuntimeException("SM2 加密失败", e);
        }
    }

    public static String decryptHexStr(String privateKeyHex, String cipherHex) {
        return new String(decryptHex(privateKeyHex, cipherHex, SM2Engine.Mode.C1C2C3));
    }

    public static String decryptHexStr(String privateKeyHex, String cipherHex, SM2Engine.Mode mode) {
        return new String(decryptHex(privateKeyHex, cipherHex, mode));
    }

    /**
     * 使用私钥解密
     * @param privateKeyHex 16 进制私钥，32 字节 D
     * @param cipherHex 16 进制密文
     * @return 明文
     */
    public static byte[] decryptHex(String privateKeyHex, String cipherHex, SM2Engine.Mode mode) {
        try {
            ECPrivateKeyParameters privateKeyParameters = createPrivateKey(privateKeyHex);
            SM2Engine engine = new SM2Engine(mode);
            engine.init(false, privateKeyParameters);
            byte[] cipher = Hex.decode(cipherHex);
            return engine.processBlock(cipher, 0, cipher.length);
        } catch (InvalidCipherTextException e) {
            throw new RuntimeException("SM2 解密失败", e);
        }
    }
}
