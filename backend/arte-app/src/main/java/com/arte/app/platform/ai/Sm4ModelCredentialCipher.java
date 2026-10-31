package com.arte.app.platform.ai;

import com.arte.ainew.pojo.model.ModelDrafts.Credential;
import com.arte.ainew.spi.model.ModelCredentialCipher;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.utils.crypto.Sm4Util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SM4-GCM 凭据加密：认证资源、空间、责任主体及凭据代次，不将加密密钥轮换与幂等指纹轮换混用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public final class Sm4ModelCredentialCipher implements ModelCredentialCipher {

    private static final String FORMAT = "sm4-gcm:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_PACKED_BYTES = 16384;

    private final String active;
    private final String fingerprintKey;
    private final Map<String, byte[]> keys;

    public Sm4ModelCredentialCipher(ModelManagementProperties properties) {
        active = properties.activeKeyId();
        fingerprintKey = properties.fingerprintKeyId();
        var result = new HashMap<String, byte[]>();
        try {
            properties.encryptionKeys().forEach((id, encoded) -> {
                if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException();
                byte[] key = Base64.getDecoder().decode(encoded);
                // 旧 32 字节密钥只能显式保留为稳定 HMAC 指纹密钥，不截断或用作 SM4 加密。
                if (key.length != 16 && !(id.equals(fingerprintKey) && key.length == 32))
                    throw new IllegalArgumentException();
                result.put(id, key);
            });
            if (active == null || !result.containsKey(active) || result.get(active).length != 16
                    || fingerprintKey == null || !result.containsKey(fingerprintKey) || result.size() > 16)
                throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Model credential encryption requires a valid SM4 key ring with a 16-byte active key");
        }
        keys = Map.copyOf(result);
    }

    @Override
    public String activeKeyId() {
        return active;
    }

    @Override
    public String encrypt(String plaintext, String aad) {
        byte[] input = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = Sm4Util.generateNonce();
            byte[] encrypted = Sm4Util.encryptGCM(input, keys.get(active), nonce, aad.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return FORMAT + Base64.getEncoder().encodeToString(packed);
        } catch (Exception failure) {
            throw unavailable();
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    @Override
    public String decrypt(Credential credential, String aad) {
        byte[] clear = null;
        try {
            byte[] key = keys.get(credential.keyId());
            String ciphertext = credential.ciphertext();
            if (key == null || key.length != 16 || credential.revoked() || ciphertext == null
                    || !ciphertext.startsWith(FORMAT) || ciphertext.length() > FORMAT.length() + ((MAX_PACKED_BYTES + 2) / 3) * 4)
                throw unavailable();
            byte[] packed = Base64.getDecoder().decode(ciphertext.substring(FORMAT.length()));
            if (packed.length < NONCE_BYTES + TAG_BYTES || packed.length > MAX_PACKED_BYTES) throw unavailable();
            byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            clear = Sm4Util.decryptGCM(encrypted, key, nonce, aad.getBytes(StandardCharsets.UTF_8));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw unavailable();
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    @Override
    public String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keys.get(fingerprintKey), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("model-idempotency-v1:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "model-credential");
    }
}
