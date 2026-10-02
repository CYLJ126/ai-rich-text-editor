package com.arte.app.ainew;

import com.arte.base.model.identity.ExecutionScope;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ModelKeys {
    private ModelKeys() {
    }

    static String scope(ExecutionScope scope) {
        return hash(scope.tenantId(), scope.workspaceId(), scope.principal().type().name(), scope.principal().principalId());
    }

    static String hash(String... values) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF("arte.ai.model.v1");
            for (String value : values) out.writeUTF(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("invalid model identifier", failure);
        }
    }

    static String digest(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
