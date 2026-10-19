package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 * TODO 后续需评估性能，是否需要优化。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface SystemMapper {

    /**
     * 数据库当前时间保留毫秒，用于执行期限及租约仲裁；每次读取都访问 JDBC。
     */
    Timestamp databaseTime();

    /**
     * 新建或无修改更新锁行，两个分支均持排他记录锁至事务结束；不能替换为 INSERT IGNORE。
     */
    int ensureLock(@Param("key") String key);

    String lock(@Param("key") String key);
}
