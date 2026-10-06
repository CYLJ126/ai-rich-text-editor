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
    PayloadRow context(@Param("idKey") String idKey);

    int insertContext(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey,
                      @Param("digest") String digest, @Param("snapshot") String snapshot);

    PayloadRow result(@Param("idKey") String idKey);

    int insertResult(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey,
                     @Param("invocationKey") String invocationKey, @Param("attemptKey") String attemptKey,
                     @Param("resultKey") String resultKey, @Param("resultType") String resultType,
                     @Param("partial") int partial, @Param("digest") String digest, @Param("snapshot") String snapshot);
}
