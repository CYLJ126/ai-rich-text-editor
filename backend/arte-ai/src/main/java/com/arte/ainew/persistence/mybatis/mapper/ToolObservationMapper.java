package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 只访问已按 owner／受理时刻建立索引的投影及有界主键集合。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public interface ToolObservationMapper {

    record Row(String invocationKey, long acceptedAt, String observation, String digest) {
    }

    List<Row> scan(@Param("owner") String owner, @Param("from") long from, @Param("until") long until,
                   @Param("binding") String binding, @Param("afterAt") Long afterAt, @Param("afterKey") String afterKey, @Param("limit") int limit);

    List<String> legacy(@Param("owner") String owner);

    List<String> attempts(@Param("keys") List<String> keys);

    List<String> settlements(@Param("keys") List<String> keys);

    List<String> approvals(@Param("keys") List<String> keys, @Param("owner") String owner);

    List<com.arte.ainew.persistence.mybatis.mapper.BudgetMapper.ToolBill> bills(@Param("keys") List<String> keys, @Param("owner") String owner);
}
