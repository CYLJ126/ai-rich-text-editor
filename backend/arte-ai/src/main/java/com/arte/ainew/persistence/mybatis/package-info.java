/**
 * 独立 MyBatis 同库事务适配器。阻塞 JDBC 操作在调用方提供的有界 Scheduler 上执行；
 * 每次订阅在同一工作线程完成整个 Spring 事务。不得接入旧 AI 的 Mapper 扫描或审计／权限插件。
 */
package com.arte.ainew.persistence.mybatis;
