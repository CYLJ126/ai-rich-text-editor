package com.arte.app.execution.support;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.observability.AuditRecord;
import com.arte.base.model.resource.ResourceRef;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 私有 v1 二进制存储格式，禁止任意 Java 反序列化；审计事实限制为 64 KiB。
 */
final class SupportEncoding {
    private SupportEncoding() {
    }

    static String digest(byte[] content) {
        return "sha256:" + HexFormat.of().formatHex(sha256().digest(content));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("digest unavailable", e);
        }
    }

    static String scopeKey(ExecutionScope scope) {
        return digest(encode(out -> scope(out, scope))).substring(7);
    }

    static byte[] owner(ResourceRef ref) {
        return encode(out -> {
            field(out, "arte.resource.v1");
            resource(out, ref);
        });
    }

    static ResourceRef owner(byte[] bytes) {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!"arte.resource.v1".equals(field(in)))
                throw new IllegalStateException("unsupported artifact owner encoding");
            var ref = new ResourceRef(field(in), field(in), field(in), field(in), field(in), field(in));
            if (in.available() != 0) throw new IllegalStateException("invalid artifact owner encoding");
            return ref;
        } catch (IOException e) {
            throw new IllegalStateException("invalid artifact owner encoding", e);
        }
    }

    static byte[] audit(AuditRecord record) {
        return encode(out -> {
            field(out, "arte.audit.v1");
            field(out, record.eventId());
            field(out, record.eventType());
            scope(out, record.scope());
            field(out, record.executor().type().name());
            field(out, record.executor().principalId());
            field(out, record.traceId());
            field(out, record.executionId());
            field(out, record.occurredAt().toString());
            field(out, record.outcome().name());
            out.writeInt(record.resources().size());
            for (var ref : record.resources()) resource(out, ref);
            out.writeInt(record.reasonCodes().size());
            for (var code : record.reasonCodes().stream().sorted().toList()) field(out, code);
            out.writeInt(record.policyVersions().size());
            for (var key : record.policyVersions().keySet().stream().sorted().toList()) {
                field(out, key);
                field(out, record.policyVersions().get(key));
            }
        });
    }

    private static void scope(DataOutputStream out, ExecutionScope scope) throws IOException {
        field(out, "arte.scope.v1");
        field(out, scope.tenantId());
        field(out, scope.workspaceId());
        field(out, scope.principal().type().name());
        field(out, scope.principal().principalId());
    }

    private static void resource(DataOutputStream out, ResourceRef ref) throws IOException {
        field(out, ref.resourceType());
        field(out, ref.resourceId());
        field(out, ref.version());
        field(out, ref.draftId());
        field(out, ref.rangeRef());
        field(out, ref.contentDigest());
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String field(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static byte[] encode(Encoder encoder) {
        try {
            var buffer = new ByteArrayOutputStream();
            var limited = new FilterOutputStream(buffer) {
                int count;

                @Override
                public void write(int value) throws IOException {
                    check(1);
                    out.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int size) throws IOException {
                    check(size);
                    out.write(bytes, offset, size);
                }

                void check(int size) throws IOException {
                    if (size > 65535 - count) throw new IOException("metadata too large");
                    count += size;
                }
            };
            try (var out = new DataOutputStream(limited)) {
                encoder.encode(out);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("support metadata encoding failed", e);
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void encode(DataOutputStream out) throws IOException;
    }
}
