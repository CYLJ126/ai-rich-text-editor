package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface SystemMapper {
    Timestamp databaseTime();

    int ensureLock(@Param("key") String key);

    String lock(@Param("key") String key);
}
