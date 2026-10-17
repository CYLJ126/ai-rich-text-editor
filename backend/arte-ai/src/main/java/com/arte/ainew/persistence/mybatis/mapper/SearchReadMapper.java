package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SearchReadMapper {

    record ConversationRow(String idKey, String snapshot, String title, Boolean active) {
    }

    List<ConversationRow> conversations(@Param("owner") String owner, @Param("keys") List<String> keys);

    List<String> turns(@Param("conversations") List<String> conversations, @Param("keys") List<String> keys);

    List<String> invocations(@Param("owner") String owner, @Param("keys") List<String> keys);

    String target(@Param("conversation") String conversation, @Param("key") String key);

    List<String> range(@Param("conversation") String conversation, @Param("after") long after, @Param("backward") boolean backward, @Param("size") int size);

    long exists(@Param("conversation") String conversation, @Param("after") long after, @Param("backward") boolean backward);
}
