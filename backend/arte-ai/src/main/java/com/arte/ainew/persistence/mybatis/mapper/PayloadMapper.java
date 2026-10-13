package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.PayloadRow;
import org.apache.ibatis.annotations.Param;

/**
 * 新 AI 的不可变上下文／结果字节；仅注册到隔离 SqlSessionFactory。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public interface PayloadMapper {
    java.util.List<com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.ResultBatchRow> results(@Param("ownerKey") String ownerKey, @Param("keys") java.util.List<String> keys);

    PayloadRow context(@Param("idKey") String idKey);

    int insertContext(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey,
                      @Param("digest") String digest, @Param("workspaceSnapshot") String workspaceSnapshot);

    PayloadRow result(@Param("idKey") String idKey);

    int insertResult(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey,
                     @Param("invocationKey") String invocationKey, @Param("attemptKey") String attemptKey,
                     @Param("resultKey") String resultKey, @Param("resultType") String resultType,
                     @Param("partial") int partial, @Param("digest") String digest, @Param("workspaceSnapshot") String workspaceSnapshot);
}
