package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 生命周期状态与命令审计的固定 SQL，只注册到隔离的执行工厂。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public interface ToolReleaseMapper {
    /**
     * 当前状态行；索引字段读取时必须与白名单快照逐项核对。
     */
    record Row(String toolKey, String compatibleFromKey, long version, String state,
               String payloadDigest, String snapshot, long updatedMicros) {
    }

    /**
     * 不可变命令回执兼审计；actorKey 和 requestDigest 共同核对重放语义。
     */
    record CommandRow(String commandKey, String actorKey, String toolKey, String requestDigest,
                      String outcome, String action, String payloadDigest, String snapshot, long recordedMicros) {
    }

    Row toolRelease(@Param("tool") String tool, @Param("lock") boolean lock);

    Row toolReleaseSuccessor(@Param("from") String from);

    CommandRow toolReleaseCommand(@Param("key") String key);

    int insertToolRelease(@Param("r") Row row);

    int updateToolRelease(@Param("r") Row row, @Param("expected") long expected);

    int insertToolReleaseCommand(@Param("r") CommandRow row);

    List<CommandRow> toolReleaseHistory(@Param("tool") String tool, @Param("after") String after, @Param("limit") int limit);
}
