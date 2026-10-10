package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformOutboxDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformOutboxMapper extends BaseMapper<PlatformOutboxDto> {

    int insertEvent(@Param("row") PlatformOutboxDto row);

    PlatformOutboxDto selectCurrent(@Param("id") String id);

    LocalDateTime databaseTime();

    List<PlatformOutboxDto> claimReady(@Param("limit") int limit);

    int lease(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);

    int acknowledge(@Param("id") String id, @Param("token") String token);

    int retry(@Param("id") String id, @Param("token") String token, @Param("availableAt") LocalDateTime availableAt);
}
