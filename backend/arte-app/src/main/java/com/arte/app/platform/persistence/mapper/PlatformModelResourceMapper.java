package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformModelResourceDto;
import com.arte.app.pojo.platform.PlatformModelVersionDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 独立平台工厂；所有读取按精确身份，写入明确 CAS／当前读。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public interface PlatformModelResourceMapper extends BaseMapper<PlatformModelResourceDto> {

    List<PlatformModelResourceDto> candidates(@Param("tenant") String tenant, @Param("workspace") String workspace,
                                              @Param("after") String after, @Param("limit") int limit);

    PlatformModelResourceDto resource(@Param("key") String key, @Param("current") boolean current);

    int insertResource(@Param("row") PlatformModelResourceDto row);

    int replaceResource(@Param("row") PlatformModelResourceDto row, @Param("expected") long expected);

    PlatformModelVersionDto binding(@Param("key") String key, @Param("current") boolean current);

    int insertVersion(@Param("row") PlatformModelVersionDto row);

    List<PlatformModelVersionDto> versions(@Param("key") String resourceKey, @Param("size") long size, @Param("offset") long offset);

    List<String> currentVersionKeys(@Param("key") String resourceKey);

    Map<String, Object> authority(@Param("key") String key);

    int insertAuthority(@Param("key") String key, @Param("type") String type, @Param("id") String id, @Param("version") String version, @Param("source") int source, @Param("digest") String digest);
}
