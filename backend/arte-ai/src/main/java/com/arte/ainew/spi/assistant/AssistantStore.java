package com.arte.ainew.spi.assistant;

import com.arte.ainew.pojo.assistant.Assistants.Draft;
import com.arte.ainew.pojo.assistant.Assistants.Release;
import com.arte.ainew.pojo.assistant.Assistants.Resource;
import com.arte.ainew.pojo.assistant.Assistants.Trial;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;

import java.util.List;
import java.util.Optional;

/**
 * 助手持久化接口
 * <p>
 * 提供资源、草稿、发布版本和试用记录的原始数据访问，供助手管理、配置解析和试用校验复用。
 * <p>
 * 当前读和写入加入调用方的平台事务。调用方负责授权、空间归属校验以及关联数据的事务编排。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:09 ✾
 */
public interface AssistantStore {

    /**
     * 按资源引用查找助手资源，供管理操作和权限校验定位助手的归属、状态及版本。
     *
     * @param ref     助手资源引用，包含租户、资源类型和助手标识。
     * @param current 为 {@code true} 时在当前可写事务中加锁读取，为 {@code false} 时执行普通读取。
     * @return 匹配的助手资源。引用类型不符或记录不存在时返回空。
     */
    Optional<Resource> find(ResourceRef ref, boolean current);

    /**
     * 在指定工作空间内按稳定的聊天配置标识定位助手资源，供会话配置解析和历史摘要查询使用。
     *
     * @param scope 助手所属的租户和工作空间。
     * @param profileId 聊天配置标识，各发布版本共用此标识。
     * @param current 为 {@code true} 时在当前可写事务中加锁读取，为 {@code false} 时执行普通读取。
     * @return 匹配的助手资源，不存在时返回空。
     */
    Optional<Resource> findByProfile(Scope scope, String profileId, boolean current);

    /**
     * 读取指定助手当前保存的草稿，供编辑、复制、试用和发布时核对内容及修订号。
     *
     * @param assistantId 草稿所属的助手资源标识。
     * @param current 为 {@code true} 时在当前可写事务中加锁读取，为 {@code false} 时执行普通读取。
     * @return 当前草稿，不存在时返回空。
     */
    Optional<Draft> draft(String assistantId, boolean current);

    /**
     * 读取指定助手的精确发布版本，供会话输入组装及历史版本查询使用。在可写事务中读取时锁定对应记录。
     *
     * @param assistantId 发布版本所属的助手资源标识。
     * @param version 要读取的发布版本号。
     * @return 匹配的发布快照，不存在时返回空。
     */
    Optional<Release> release(String assistantId, long version);

    /**
     * 在当前可写事务中加锁读取指定助手的试用记录，供执行复核和发布校验使用。
     *
     * @param assistantId 试用记录所属的助手资源标识。
     * @param trialId 要读取的试用记录标识。
     * @return 匹配的试用记录，不存在时返回空。
     */
    Optional<Trial> trial(String assistantId, String trialId);

    /**
     * 按助手标识升序扫描工作空间内的资源候选，供上层目录执行逐项授权和名称过滤。
     *
     * @param scope 要扫描的租户和工作空间。
     * @param afterId 只读取标识排在此游标之后的记录，首次扫描时为空。
     * @param activeOnly 为 {@code true} 时仅扫描已发布状态的助手，为 {@code false} 时扫描所有状态。
     * @param limit 本次扫描最多读取的记录数，由调用方提供有效的正整数上限。
     * @return 按助手标识升序返回的资源候选。
     */
    List<Resource> scan(Scope scope, String afterId, boolean activeOnly, int limit);

    /**
     * 在当前可写事务中按发布版本号升序加锁读取发布历史，供管理端分页展示版本列表。
     *
     * @param assistantId 发布历史所属的助手资源标识。
     * @param afterVersion 只读取大于此版本号的记录，首次查询时传零。
     * @param limit 本次最多读取的发布版本数，调用方可多取一条以判断是否存在下一页。
     * @return 按发布版本号升序返回的发布快照。
     */
    List<Release> versions(String assistantId, long afterVersion, int limit);

    /**
     * 在当前可写事务中一并写入新助手资源和初始草稿，供创建及复制助手使用。
     *
     * @param resource 要创建的助手资源。
     * @param draft 初始草稿，其助手标识须与新资源一致。
     */
    void insert(Resource resource, Draft draft);

    /**
     * 在当前可写事务中按预期修订号更新草稿，保存新内容并通过修订号检测并发修改。
     *
     * @param draft 要写入的新草稿，包含所属助手标识和新修订号。
     * @param expectedRevision 写入前预期的草稿修订号，用于检测并发更新。
     * @return 恰好更新一条记录时为 {@code true}，无匹配记录时为 {@code false}。
     */
    boolean replaceDraft(Draft draft, long expectedRevision);

    /**
     * 在当前可写事务中按预期资源版本更新助手状态、当前发布版本和变更元数据。
     * 用于草稿保存、发布和停用时推进资源状态。
     *
     * @param resource 携带更新后状态、发布版本和资源版本的助手资源。
     * @param expectedVersion 写入前预期的资源乐观锁版本。
     * @return 恰好更新一条记录时为 {@code true}，无匹配记录时为 {@code false}。
     */
    boolean replaceResource(Resource resource, long expectedVersion);

    /**
     * 在当前可写事务中追加不可变的发布快照，当前发布版本由调用方在同一事务中更新。
     *
     * @param release 已完成发布校验的助手版本，包含冻结内容和试用来源。
     */
    void insertRelease(Release release);
}
