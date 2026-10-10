package com.arte.app.platform.persistence;

import com.arte.core.utils.platform.PlatformChecks;

import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
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

    public RedisWorkspaceCursorStore(
            RedissonClient redis, PlatformJson json, Duration ttl, String prefix) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.prefix = prefix;
    }

    @Override
    public String save(Cursor cursor) {
        String token = UUID.randomUUID().toString();
        redis.<String>getBucket(prefix + token, StringCodec.INSTANCE).set(json.write(cursor), ttl);
        return token;
    }

    @Override
    public Cursor load(String token) {
        PlatformChecks.id(token, "cursor");
        UUID.fromString(token);
        String value = redis.<String>getBucket(prefix + token, StringCodec.INSTANCE).get();
        if (value == null)
            throw new IllegalArgumentException("Unknown or expired workspace cursor");
        return json.read(value, Cursor.class);
    }
}
