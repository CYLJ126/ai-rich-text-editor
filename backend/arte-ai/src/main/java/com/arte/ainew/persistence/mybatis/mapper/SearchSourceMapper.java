package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SearchSourceMapper {
    record Metadata(String title, boolean active, String ownerKey, String conversationId) {
    }

    List<String> keys(@Param("kind") String kind, @Param("after") String after, @Param("size") int size);

    String snapshot(@Param("kind") String kind, @Param("key") String key);

    Metadata metadata(@Param("key") String key);

    Long generatedMicros(@Param("conversation") String conversation, @Param("fact") String fact);
}
