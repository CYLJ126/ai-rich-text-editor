package com.arte.ainew.infrastructure.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.search.SearchCursor;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.SearchSession;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.spi.search.ConversationSearchSessions;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 单次 Lua 仲裁名额、租约、PIT 引用和 TTL；集群内同主体键使用相同 hash tag。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class RedisConversationSearchSessions implements ConversationSearchSessions {

    private final RedissonClient redis;
    private final NewAiSearchProperties properties;
    private final String script;
    private final JsonMapper json = JsonMapper.builder().build();

    public RedisConversationSearchSessions(RedissonClient redis, NewAiSearchProperties properties) {
        this.redis = Objects.requireNonNull(redis);
        this.properties = Objects.requireNonNull(properties);
        try {
            script = new ClassPathResource("ainew/search/session.lua").getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Missing search session script", failure);
        }
    }

    private List<Object> keys(ExecutionOwner owner, String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new SearchFailure(SearchFailure.Code.INVALID_CURSOR);
        String tag = CanonicalJson.key(owner.tenantId(), owner.subjectId());
        String prefix = properties.session().namespace() + ":{" + tag + "}:";
        return List.of(prefix + "active", prefix + id);
    }

    private long timeout(Duration remaining) {
        long ms = Math.min(properties.session().redisTimeout().toMillis(), remaining.toMillis());
        if (ms < 1) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
        return ms;
    }

    private String eval(String lua, List<Object> keys, Duration timeout, Object... args) {
        try {
            return redis.getScript(StringCodec.INSTANCE).<String>evalAsync(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.VALUE, keys, args)
                    .toCompletableFuture().get(timeout(timeout), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, failure);
        } catch (Exception failure) {
            throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    private SearchSession call(String operation, ExecutionOwner owner, String id, Map<String, Object> payload, Duration timeout) {
        payload.put("id", id);
        payload.put("ownerKey", SearchCursor.owner(owner));
        long lease = Math.max(30000, properties.limits().requestTimeout().toMillis() + properties.session().redisTimeout().toMillis() * 2);
        String result = eval(script, keys(owner, id), timeout, operation, json.writeValueAsString(payload), lease,
                properties.session().idleTimeout().toMillis(), properties.session().maxLifetime().toMillis(), properties.session().maxPerSubject());
        if (result == null || result.isEmpty()) return null;
        if (result.startsWith("!")) throw new SearchFailure(switch (result) {
            case "!LIMIT" -> SearchFailure.Code.SEARCH_LIMIT_REACHED;
            case "!BUSY" -> SearchFailure.Code.SEARCH_BUSY;
            case "!BOUND" -> SearchFailure.Code.INVALID_CURSOR;
            default -> SearchFailure.Code.SEARCH_EXPIRED;
        });
        try {
            return json.readValue(result, SearchSession.class);
        } catch (RuntimeException invalid) {
            throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, invalid);
        }
    }

    private Map<String, Object> token(SearchSession s) {
        return new LinkedHashMap<>(Map.of("operationToken", s.operationToken()));
    }

    @Override
    public SearchSession reserve(ExecutionOwner owner, String id, String queryHash, int size, String rules, Duration timeout) {
        var seed = new LinkedHashMap<String, Object>();
        seed.put("queryHash", queryHash);
        seed.put("size", size);
        seed.put("rulesVersion", rules);
        seed.put("secret", SearchCursor.randomKey());
        seed.put("pitId", "");
        seed.put("generation", "");
        seed.put("operationToken", UUID.randomUUID().toString());
        return call("RESERVE", owner, id, seed, timeout);
    }

    @Override
    public SearchSession acquire(ExecutionOwner owner, String id, String queryHash, int size, Duration timeout) {
        return call("ACQUIRE", owner, id, new LinkedHashMap<>(Map.of("queryHash", queryHash, "size", size, "operationToken", UUID.randomUUID().toString())), timeout);
    }

    @Override
    public SearchSession pit(ExecutionOwner owner, SearchSession lease, ConversationSearchIndex.Pit pit, long expiresAt, Duration timeout) {
        var args = token(lease);
        args.put("pitId", pit.id());
        args.put("generation", pit.generation());
        args.put("pitExpiresAt", expiresAt);
        return call("PIT", owner, lease.id(), args, timeout);
    }

    @Override
    public SearchSession complete(ExecutionOwner owner, SearchSession lease, Duration timeout) {
        return call("COMPLETE", owner, lease.id(), token(lease), timeout);
    }

    @Override
    public void release(ExecutionOwner owner, SearchSession lease, Duration timeout) {
        call("RELEASE", owner, lease.id(), token(lease), timeout);
    }

    @Override
    public SearchSession close(ExecutionOwner owner, String id, Duration timeout) {
        return call("CLOSE", owner, id, new LinkedHashMap<>(), timeout);
    }

    @Override
    public SearchSession cancelReservation(ExecutionOwner owner, String id, Duration timeout) {
        return call("CANCEL", owner, id, new LinkedHashMap<>(), timeout);
    }

    @Override
    public String historySigningKey(Duration timeout) {
        return eval("local k=redis.call('GET',KEYS[1]);if not k then redis.call('SET',KEYS[1],ARGV[1]);k=ARGV[1] end;return k",
                List.of(properties.session().namespace() + ":history-signing:v1"), timeout, SearchCursor.randomKey());
    }
}
