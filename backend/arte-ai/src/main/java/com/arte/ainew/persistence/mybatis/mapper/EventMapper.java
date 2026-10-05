package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface EventMapper {
    long nextSequence(@Param("idKey") String idKey);

    int insertEvent(
            @Param("invocationKey") String invocationKey,
            @Param("sequenceNo") long sequenceNo,
            @Param("snapshot") String snapshot);

    int saveNextSequence(@Param("nextSequence") long nextSequence, @Param("idKey") String idKey);

    long writtenBytes(@Param("idKey") String idKey);

    int addWrittenBytes(@Param("bytes") long bytes, @Param("idKey") String idKey);

    List<String> events(
            @Param("invocationKey") String invocationKey,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

    long retainedAfter(@Param("idKey") String idKey);

    int discardEvents(@Param("invocationKey") String invocationKey, @Param("throughSequence") long throughSequence);

    int saveRetainedAfter(@Param("retainedAfter") long retainedAfter, @Param("idKey") String idKey);
}
