package com.arte.app.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实际模型不可变发布证据的专用 SQL，只使用平台数据源，不保存密钥明文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public interface PlatformModelPublicationMapper {
    Row findShared(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("binding") String binding, @Param("current") boolean current);

    List<Row> listShared(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("current") boolean current);

    /**
     * 发布证据数据库行。
     *
     * @param bindingKey 精确绑定索引键
     * @param digest     发布证据的内容摘要
     * @param payload    发布证据 JSON
     */
    record Row(String bindingKey, String digest, String payload) {
    }

    /**
     * 查询同空间精确发布。
     *
     * @param scopeKey   空间索引键
     * @param bindingKey 精确绑定索引键
     * @param current    是否锁定当前行
     * @return 发布证据或空
     */
    Row find(@Param("scopeKey") String scopeKey, @Param("bindingKey") String bindingKey, @Param("current") boolean current);

    /**
     * 有界查询同空间发布，额外读取一项用于识别超限。
     *
     * @param scopeKey 空间索引键
     * @param current  是否锁定当前行
     * @return 至多 257 项
     */
    List<Row> list(@Param("scopeKey") String scopeKey, @Param("current") boolean current);

    /**
     * 插入不可变证据，唯一键冲突不覆盖。
     *
     * @param scopeKey    空间索引键
     * @param resourceKey 稳定模型索引键
     * @param row         精确发布证据
     * @return 插入行数
     */
    int insert(@Param("scopeKey") String scopeKey, @Param("resourceKey") String resourceKey, @Param("row") Row row);
}
