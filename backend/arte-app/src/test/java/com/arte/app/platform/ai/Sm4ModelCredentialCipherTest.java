package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultModelDraftService;
import com.arte.ainew.pojo.model.ModelDrafts.Credential;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;
import static org.junit.jupiter.api.Assertions.*;

class Sm4ModelCredentialCipherTest {

    String key(int value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    Sm4ModelCredentialCipher cipher(String active, Map<String, String> keys) {
        return new Sm4ModelCredentialCipher(new ModelManagementProperties(true, active, "old", keys));
    }

    @Test
    void encryptionUsesFreshNonceAndBindsIdentityAndGeneration() {
        var cipher = cipher("old", Map.of("old", key(1)));
        var ref = new ResourceRef("tenant", MODEL, "model");
        var scope = new Scope("tenant", "workspace");
        String aad = DefaultModelDraftService.associatedData(ref, scope, "provider", 1);
        String encrypted = cipher.encrypt("private-api-key", aad);
        assertTrue(encrypted.startsWith("sm4-gcm:v1:"));
        assertNotEquals(encrypted, cipher.encrypt("private-api-key", aad));
        var credential = new Credential(ref, scope, new DefinitionRef("secret", "s", "v1"), "provider", "old", encrypted, false, 1, Instant.now());
        assertEquals("private-api-key", cipher.decrypt(credential, aad));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(credential, DefaultModelDraftService.associatedData(ref, scope, "other", 1)));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(credential, DefaultModelDraftService.associatedData(ref, scope, "provider", 2)));
        byte[] bytes = Base64.getDecoder().decode(encrypted.substring("sm4-gcm:v1:".length()));
        bytes[bytes.length - 1] ^= 1;
        var tampered = new Credential(ref, scope, credential.secret(), "provider", "old", "sm4-gcm:v1:" + Base64.getEncoder().encodeToString(bytes), false, 1, credential.updatedAt());
        assertThrows(RuntimeException.class, () -> cipher.decrypt(tampered, aad));
    }

    @Test
    void rotationRetainsOldDecryptionAndStableIdempotencyFingerprint() {
        var old = cipher("old", Map.of("old", key(1)));
        var rotated = cipher("new", Map.of("old", key(1), "new", key(2)));
        assertEquals(old.fingerprint("same-operation-key"), rotated.fingerprint("same-operation-key"));
        var ref = new ResourceRef("t", MODEL, "m");
        var scope = new Scope("t", "s");
        var c = new Credential(ref, scope, new DefinitionRef("secret", "s", "v1"), "p", "old", old.encrypt("key", "aad"), false, 1, Instant.now());
        assertEquals("key", rotated.decrypt(c, "aad"));
        assertEquals("new", rotated.activeKeyId());
    }

    @Test
    void missingOrInvalidKeyFailsClosedWithoutEchoingKeyMaterial() {
        assertThrows(IllegalArgumentException.class, () -> cipher("old", Map.of()));
        String invalid = "invalid-key-value";
        var failure = assertThrows(IllegalArgumentException.class, () -> cipher("old", Map.of("old", invalid)));
        assertFalse(failure.toString().contains(invalid));
        assertFalse(new ModelManagementProperties(true, "old", "old", Map.of("old", key(1))).toString().contains(key(1)));
        assertThrows(IllegalArgumentException.class, () -> cipher("old", Map.of("old", Base64.getEncoder().encodeToString(new byte[32]))));
        assertThrows(IllegalArgumentException.class, () -> cipher("old", Map.of("old", key(1), "unused-aes", Base64.getEncoder().encodeToString(new byte[32]))));
    }

    @Test
    void originalFingerprintKeyCanBeRetainedIndependentlyOfSm4Encryption() throws Exception {
        byte[] original = new byte[32];
        Arrays.fill(original, (byte) 7);
        var cipher = cipher("sm4-new", Map.of("old", Base64.getEncoder().encodeToString(original), "sm4-new", key(2)));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(original, "HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal("model-idempotency-v1:same-request".getBytes(java.nio.charset.StandardCharsets.UTF_8))), cipher.fingerprint("same-request"));
        var ref = new ResourceRef("t", MODEL, "m");
        var scope = new Scope("t", "s");
        var encrypted = cipher.encrypt("key", "aad");
        assertEquals("key", cipher.decrypt(new Credential(ref, scope, new DefinitionRef("secret", "s", "v1"), "p", "sm4-new", encrypted, false, 1, Instant.now()), "aad"));
    }

    @Test
    void legacyCiphertextUnknownFormatsAndRevokedCredentialsFailClosed() {
        var cipher = cipher("old", Map.of("old", key(1)));
        var ref = new ResourceRef("t", MODEL, "m");
        var scope = new Scope("t", "s");
        String encrypted = cipher.encrypt("key", "aad");
        for (String invalid : List.of(encrypted.substring("sm4-gcm:v1:".length()), encrypted.replace("v1:", "v2:"), "sm4-gcm:v1:!", "sm4-gcm:v1:" + "A".repeat(25000))) {
            var value = new Credential(ref, scope, new DefinitionRef("secret", "s", "v1"), "p", "old", invalid, false, 1, Instant.now());
            assertEquals(com.arte.core.enums.platform.PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE,
                    assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> cipher.decrypt(value, "aad")).getCode());
        }
        var revoked = new Credential(ref, scope, new DefinitionRef("secret", "s", "v1"), "p", "old", null, true, 2, Instant.now());
        assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> cipher.decrypt(revoked, "aad"));
        var unknownKey = new Credential(ref, scope, new DefinitionRef("secret", "s", "v1"), "p", "unknown", encrypted, false, 1, Instant.now());
        assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> cipher.decrypt(unknownKey, "aad"));
    }
}
