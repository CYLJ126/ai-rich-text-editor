/**
 * 新 AI 私有 SQL 端口和数据库投影。只由独立工厂显式加载，不注册为默认 Spring Mapper Bean。
 * XML 使用绑定参数和明确表名；原子业务操作、归属、版本与租约校验保留在复合事务适配器中。
 */
package com.arte.ainew.persistence.mybatis.mapper;
