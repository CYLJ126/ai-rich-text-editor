package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DispatchLimitMapper {

    record Permit(long token, String tenantKey, String userKey, String modelKey, long leaseUntil) {
    }

    record Window(long startsAt, int requests) {
    }

    List<Permit> permit(@Param("key") String key);

    long active(@Param("dimension") String dimension, @Param("scope") String scope, @Param("now") long now);

    List<Window> window(@Param("key") String key);

    int insertWindow(@Param("key") String key, @Param("startsAt") long startsAt);

    int saveWindow(@Param("key") String key, @Param("startsAt") long startsAt, @Param("requests") int requests);

    int insertPermit(@Param("key") String key, @Param("token") long token, @Param("tenant") String tenant,
                     @Param("user") String user, @Param("model") String model, @Param("expiry") long expiry);

    int renew(@Param("key") String key, @Param("token") long token, @Param("expiry") long expiry);

    int release(@Param("key") String key, @Param("token") long token);

    int expire(@Param("now") long now);
}
