package com.nip.core.utils.crypto;

import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * SM4 算法工具类
 *
 * @author zhangsc
 * @since 2026/3/20 10:34
 */
public class Sm4Util extends AbstractGmUtil {

    /**
     * 生成 SM4 密钥
     */
    public static byte[] generateKey() {
        return generateIV();
    }

    /**
     * 生成 SM4 密钥（十六进制字符串）
     */
    public static String generateKeyHex() {
        return Hex.toHexString(generateKey()).toUpperCase();
    }

    /**
     * SM4 ECB 模式加密
     */
    public static byte[] encryptECB(byte[] data, byte[] key) throws Exception {
        SM4Engine engine = new SM4Engine();
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(engine, new PKCS7Padding());
        cipher.init(true, new KeyParameter(key));

        byte[] output = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, output, 0);
        len += cipher.doFinal(output, len);

        return Arrays.copyOf(output, len);
    }

    /**
     * SM4 ECB 模式解密
     */
    public static byte[] decryptECB(byte[] encryptedData, byte[] key) throws Exception {
        SM4Engine engine = new SM4Engine();
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(engine, new PKCS7Padding());
        cipher.init(false, new KeyParameter(key));

        byte[] output = new byte[cipher.getOutputSize(encryptedData.length)];
        int len = cipher.processBytes(encryptedData, 0, encryptedData.length, output, 0);
        len += cipher.doFinal(output, len);

        return Arrays.copyOf(output, len);
    }

    /**
     * SM4 CBC 模式加密 - 使用 JCE 实现
     */
    public static byte[] encryptCBC(byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("SM4/CBC/PKCS7Padding", "BC");
        SecretKeySpec keySpec = new SecretKeySpec(key, "SM4");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(data);
    }

    /**
     * SM4 CBC 模式解密 - 使用 JCE 实现
     */
    public static byte[] decryptCBC(byte[] encryptedData, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("SM4/CBC/PKCS7Padding", "BC");
        SecretKeySpec keySpec = new SecretKeySpec(key, "SM4");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(encryptedData);
    }

    /**
     * SM4 GCM 模式加密
     */
    public static byte[] encryptGCM(byte[] data, byte[] key, byte[] nonce) throws Exception {
        SM4Engine engine = new SM4Engine();
        GCMBlockCipher cipher = (GCMBlockCipher) GCMBlockCipher.newInstance(engine);
        AEADParameters parameters = new AEADParameters(new KeyParameter(key), 128, nonce);
        cipher.init(true, parameters);

        byte[] output = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, output, 0);
        len += cipher.doFinal(output, len);

        return Arrays.copyOf(output, len);
    }

    /**
     * SM4 GCM 模式解密
     */
    public static byte[] decryptGCM(byte[] encryptedData, byte[] key, byte[] nonce) throws Exception {
        SM4Engine engine = new SM4Engine();
        GCMBlockCipher cipher = (GCMBlockCipher) GCMBlockCipher.newInstance(engine);
        AEADParameters parameters = new AEADParameters(new KeyParameter(key), 128, nonce);
        cipher.init(false, parameters);

        byte[] output = new byte[cipher.getOutputSize(encryptedData.length)];
        int len = cipher.processBytes(encryptedData, 0, encryptedData.length, output, 0);
        len += cipher.doFinal(output, len);

        return Arrays.copyOf(output, len);
    }

    /**
     * SM4 ECB 模式加密（字符串）
     */
    public static String encrypt(String data, String keyHex) throws Exception {
        byte[] key = Hex.decode(keyHex);
        byte[] encrypted = encryptECB(data.getBytes(StandardCharsets.UTF_8), key);
        return Hex.toHexString(encrypted).toUpperCase();
    }

    /**
     * SM4 ECB 模式解密（字符串）
     */
    public static String decrypt(String encryptedDataHex, String keyHex) throws Exception {
        byte[] key = Hex.decode(keyHex);
        byte[] encryptedData = Hex.decode(encryptedDataHex);
        byte[] decrypted = decryptECB(encryptedData, key);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * SM4 CBC 模式加密（字符串）
     */
    public static String encryptCBC(String data, String keyHex, String ivHex) throws Exception {
        byte[] key = Hex.decode(keyHex);
        byte[] iv = Hex.decode(ivHex);
        byte[] encrypted = encryptCBC(data.getBytes(StandardCharsets.UTF_8), key, iv);
        return Hex.toHexString(encrypted).toUpperCase();
    }

    /**
     * SM4 CBC 模式解密（字符串）
     */
    public static String decryptCBC(String encryptedDataHex, String keyHex, String ivHex) throws Exception {
        byte[] key = Hex.decode(keyHex);
        byte[] iv = Hex.decode(ivHex);
        byte[] encryptedData = Hex.decode(encryptedDataHex);
        byte[] decrypted = decryptCBC(encryptedData, key, iv);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * 生成随机 IV
     */
    public static byte[] generateIV() {
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * 生成随机 IV（十六进制字符串）
     */
    public static String generateIVHex() {
        return Hex.toHexString(generateIV()).toUpperCase();
    }

    /**
     * 生成随机 Nonce（用于 GCM 模式）
     */
    public static byte[] generateNonce() {
        byte[] nonce = new byte[12]; // GCM 推荐 12 字节 nonce
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /**
     * 生成随机 Nonce（十六进制字符串）
     */
    public static String generateNonceHex() {
        return Hex.toHexString(generateNonce()).toUpperCase();
    }
}
