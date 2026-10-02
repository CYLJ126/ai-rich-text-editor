package com.arte.app.security.bridge;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.EgressRequest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 接入层存储键 v1；长度编码区分 null、空串及字段边界，不保存正文、Token 或密码。
 */
final class SecurityFingerprints {
    private SecurityFingerprints() {
    }

    static String context(ExecutionContext context) {
        return digest(out -> {
            field(out, "arte.security.context.v1");
            field(out, context.scope().tenantId());
            field(out, context.scope().workspaceId());
            field(out, context.scope().principal().type().name());
            field(out, context.scope().principal().principalId());
            field(out, context.traceId());
            field(out, context.parentExecutionId());
            field(out, context.deadline() == null ? null : context.deadline().toString());
            field(out, context.cancellation() == null ? null : context.cancellation().executionId());
            out.writeInt(context.authorizationScopes().size());
            for (String action : context.authorizationScopes().stream().sorted().toList()) field(out, action);
            resource(out, context.budgetRef());
            resource(out, context.releaseRef());
            out.writeBoolean(context.idempotencyKey() != null);
            if (context.idempotencyKey() != null) {
                field(out, context.idempotencyKey().key());
                field(out, context.idempotencyKey().operation());
                field(out, context.idempotencyKey().requestDigest());
            }
        });
    }

    /**
     * consentRef 单独核对；其余完整请求固定在同意记录中，来源顺序也参与摘要。
     */
    static String egress(EgressRequest request) {
        return digest(out -> {
            field(out, "arte.security.egress.v1");
            field(out, context(request.context()));
            field(out, request.executor().type().name());
            field(out, request.executor().principalId());
            out.writeInt(request.sources().size());
            for (var source : request.sources()) {
                resource(out, source.resource());
                field(out, source.citationId());
            }
            resource(out, request.destination().connectionRef());
            field(out, request.destination().origin().toASCIIString());
            field(out, request.purpose());
            field(out, request.contentDigest());
        });
    }

    static String resource(ResourceRef ref) {
        return digest(out -> resource(out, ref));
    }

    static String values(String... values) {
        return digest(out -> {
            for (String value : values) field(out, value);
        });
    }

    private static void resource(DataOutputStream out, ResourceRef ref) throws IOException {
        out.writeBoolean(ref != null);
        if (ref == null) return;
        field(out, ref.resourceType());
        field(out, ref.resourceId());
        field(out, ref.version());
        field(out, ref.draftId());
        field(out, ref.rangeRef());
        field(out, ref.contentDigest());
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String digest(Encoder encoder) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(buffer)) {
                encoder.encode(out);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("security fingerprint unavailable", e);
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void encode(DataOutputStream out) throws IOException;
    }
}
