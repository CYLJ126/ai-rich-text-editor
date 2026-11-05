package com.arte.app.platform.persistence;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.utils.platform.PlatformChecks;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared short lived navigation state only; never caches an authorization decision.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
public final class RedisWorkspaceCursorStore implements WorkspaceCursorStore {
    private final RedissonClient redis;
    private final PlatformJson json;
    private final Duration ttl;
    private final String prefix;
    private final String tenantPrefix;

    public RedisWorkspaceCursorStore(RedissonClient redis, PlatformJson json, Duration ttl, String prefix) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.prefix = prefix;
        this.tenantPrefix = prefix + "tenant:v1:";
    }

    @Override
    public String save(Cursor cursor) {
        return save(prefix, cursor);
    }

    @Override
    public Cursor load(String token) {
        return load(prefix, token, Cursor.class);
    }

    @Override
    public String saveTenant(TenantCursor cursor) {
        return save(tenantPrefix, cursor);
    }

    @Override
    public TenantCursor loadTenant(String token) {
        return load(tenantPrefix, token, TenantCursor.class);
    }

    private String save(String namespace, Object cursor) {
        String token = UUID.randomUUID().toString();
        try {
            redis.<String>getBucket(namespace + token, StringCodec.INSTANCE).set(json.write(cursor), ttl);
        } catch (RuntimeException failure) {
            throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "workspace-cursor");
        }
        return token;
    }

    private <T> T load(String namespace, String token, Class<T> type) {
        PlatformChecks.id(token, "cursor");
        UUID.fromString(token);
        String value;
        try {
            value = redis.<String>getBucket(namespace + token, StringCodec.INSTANCE).get();
        } catch (RuntimeException failure) {
            throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "workspace-cursor");
        }
        if (value == null)
            throw new PlatformException(PlatformErrorCodeEnum.CURSOR_EXPIRED, "workspace-cursor");
        try {
            return Objects.requireNonNull(json.read(value, type));
        } catch (RuntimeException failure) {
            throw new PlatformException(PlatformErrorCodeEnum.CURSOR_EXPIRED, "workspace-cursor");
        }
    }
}
