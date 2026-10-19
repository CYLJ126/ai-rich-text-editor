package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 绑定当前指针、不可变材料和管理审计的固定 SQL；只注册到隔离工厂。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public interface ToolBindingMapper {
    /**
     * 当前指针及 owner 索引，snapshot 为白名单 ToolBindingDefinition。
     */
    record Header(String bindingKey, String ownerKey, String toolId, long version, String payloadDigest,
                  String snapshot) {
    }

    /**
     * 修订／解析材料共享的索引结构；revisionKey 指向源修订，toolKey 指向实际工具。
     */
    record Artifact(String artifactKey, String bindingKey, String revisionKey, String toolKey, String payloadDigest,
                    String snapshot) {
    }

    /**
     * 管理命令原始决定，同时充当不可变审计；拒绝允许绑定尚不存在。
     */
    record Command(String commandKey, String ownerKey, String bindingKey, String requestDigest, String payloadDigest,
                   String snapshot) {
    }

    Header toolBinding(@Param("key") String key);

    List<Header> toolBindings(@Param("owner") String owner, @Param("after") String after, @Param("limit") int limit);

    /**
     * 调用预览使用普通快照读取，不能持有管理写锁。
     */
    Header toolBindingReadOnly(@Param("key") String key);

    Artifact toolBindingRevisionReadOnly(@Param("key") String key);

    Artifact toolBindingRevision(@Param("key") String key);

    Artifact toolBindingResolution(@Param("key") String key);

    Command toolBindingCommand(@Param("key") String key);

    List<Command> toolBindingHistory(@Param("owner") String owner, @Param("binding") String binding, @Param("after") String after, @Param("limit") int limit);

    int insertToolBinding(@Param("r") Header row);

    int updateToolBinding(@Param("r") Header row, @Param("expected") long expected);

    int insertToolBindingRevision(@Param("r") Artifact row);

    int insertToolBindingResolution(@Param("r") Artifact row);

    int insertToolBindingCommand(@Param("r") Command row);
}
