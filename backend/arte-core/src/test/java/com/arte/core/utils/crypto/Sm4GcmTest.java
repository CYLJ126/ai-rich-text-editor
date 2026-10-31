package com.arte.core.utils.crypto;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Sm4GcmTest {
    byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    /**
     * Independent known-answer vector: https://www.rfc-editor.org/rfc/rfc8998.html#appendix-A.1
     */
    @Test
    void matchesRfc8998Sm4GcmVectorIncludingAadAndTag() throws Exception {
        byte[] key = hex("0123456789ABCDEFFEDCBA9876543210");
        byte[] nonce = hex("00001234567800000000ABCD");
        byte[] aad = hex("FEEDFACEDEADBEEFFEEDFACEDEADBEEFABADDAD2");
        byte[] plaintext = hex("AAAAAAAAAAAAAAAABBBBBBBBBBBBBBBB"
                + "CCCCCCCCCCCCCCCCDDDDDDDDDDDDDDDD"
                + "EEEEEEEEEEEEEEEEFFFFFFFFFFFFFFFF"
                + "EEEEEEEEEEEEEEEEAAAAAAAAAAAAAAAA");
        byte[] expected = hex("17F399F08C67D5EE19D0DC9969C4BB7D"
                + "5FD46FD3756489069157B282BB200735"
                + "D82710CA5C22F0CCFA7CBF93D496AC15"
                + "A56834CBCF98C397B4024A2691233B8D"
                + "83DE3541E4C2B58177E065A9BF7B62EC");
        assertArrayEquals(expected, Sm4Util.encryptGCM(plaintext, key, nonce, aad));
        assertArrayEquals(plaintext, Sm4Util.decryptGCM(expected, key, nonce, aad));
    }

    @Test
    void rejectsChangedKeyNonceContextCiphertextAndTag() throws Exception {
        byte[] key = Sm4Util.generateKey(), nonce = Sm4Util.generateNonce(), aad = "tenant/space/model/person/1".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = Sm4Util.encryptGCM("private-key".getBytes(StandardCharsets.UTF_8), key, nonce, aad);
        byte[] wrongKey = key.clone();
        wrongKey[0] ^= 1;
        byte[] wrongNonce = nonce.clone();
        wrongNonce[0] ^= 1;
        byte[] wrongAad = aad.clone();
        wrongAad[0] ^= 1;
        assertThrows(InvalidCipherTextException.class, () -> Sm4Util.decryptGCM(encrypted, wrongKey, nonce, aad));
        assertThrows(InvalidCipherTextException.class, () -> Sm4Util.decryptGCM(encrypted, key, wrongNonce, aad));
        assertThrows(InvalidCipherTextException.class, () -> Sm4Util.decryptGCM(encrypted, key, nonce, wrongAad));
        for (int index : new int[]{0, encrypted.length - 1}) {
            byte[] changed = encrypted.clone();
            changed[index] ^= 1;
            assertThrows(InvalidCipherTextException.class, () -> Sm4Util.decryptGCM(changed, key, nonce, aad));
        }
    }

    @Test
    void threeArgumentApiRetainsNoAadCompatibility() throws Exception {
        byte[] key = Sm4Util.generateKey(), nonce = Sm4Util.generateNonce(), plaintext = "兼容原有调用".getBytes(StandardCharsets.UTF_8);
        byte[] original = Sm4Util.encryptGCM(plaintext, key, nonce);
        assertArrayEquals(original, Sm4Util.encryptGCM(plaintext, key, nonce, new byte[0]));
        assertArrayEquals(plaintext, Sm4Util.decryptGCM(original, key, nonce));
        assertArrayEquals(plaintext, Sm4Util.decryptGCM(original, key, nonce, new byte[0]));
    }

    @Test
    void handlesEmptyAndUtf8PayloadsAndRejectsNonSm4KeyLengths() throws Exception {
        for (byte[] plaintext : new byte[][]{new byte[0], "中文凭据🔑".getBytes(StandardCharsets.UTF_8), new byte[8192]}) {
            byte[] key = Sm4Util.generateKey(), nonce = Sm4Util.generateNonce(), aad = new byte[]{1, 2, 3};
            byte[] encrypted = Sm4Util.encryptGCM(plaintext, key, nonce, aad);
            assertArrayEquals(plaintext, Sm4Util.decryptGCM(encrypted, key, nonce, aad));
        }
        assertThrows(IllegalArgumentException.class, () -> Sm4Util.encryptGCM(new byte[]{1}, new byte[32], Sm4Util.generateNonce(), new byte[0]));
    }
}
