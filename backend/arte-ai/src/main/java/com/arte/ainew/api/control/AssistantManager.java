package com.arte.ainew.api.control;

import com.arte.ainew.pojo.assistant.Assistants.*;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

/**
 * 工作空间助手的管理接口，负责草稿维护、试用后发布、停用和目录查询，并按精确版本解析已发布的聊天配置。
 * <p>
 * 操作以可信主体和工作空间为授权边界。写命令使用幂等键记录回执，修改已有助手时按相应版本校验并发更新。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:35 ✾
 */
public interface AssistantManager {

    /**
     * 在指定工作空间创建助手资源及其初始草稿。
     *
     * @param actor   发起创建的可信主体，须具备工作空间的助手创建权限。
     * @param scope   助手所属的租户和工作空间。
     * @param content 初始草稿内容，允许先保存未填写完整的配置。
     * @param meta    写命令的幂等键和追踪标识。
     * @return 包含新助手标识、资源版本和草稿修订号的命令回执。
     */
    CommandReceipt<Receipt> create(TrustedActor actor, Scope scope, Content content, CommandMeta meta);

    /**
     * 将指定发布版本或当前草稿的内容复制为同一工作空间内的新助手草稿。
     *
     * @param actor 发起复制的可信主体，须可读取源助手并在工作空间创建助手。
     * @param scope 源助手和新助手共同所属的租户与工作空间。
     * @param sourceId 源助手的资源标识。
     * @param sourceVersion 要复制的发布版本号，须大于零，与 {@code sourceDraftRevision} 二选一。
     * @param sourceDraftRevision 要复制的草稿修订号，须大于零且等于源助手的当前草稿修订号，与 {@code sourceVersion} 二选一。
     * @param meta 写命令的幂等键和追踪标识。
     * @return 新助手及其初始草稿的命令回执。
     */
    CommandReceipt<Receipt> copy(TrustedActor actor, Scope scope, String sourceId, Long sourceVersion,
                                 Long sourceDraftRevision, CommandMeta meta);

    /**
     * 按预期修订号保存草稿，内容变化时推进草稿修订号和资源版本。已发布会话继续绑定原发布版本。
     *
     * @param actor 发起保存的可信主体，须具备该助手的编辑权限。
     * @param scope 助手所属的租户和工作空间。
     * @param id 要编辑的助手资源标识。
     * @param expectedRevision 调用方读取到的草稿修订号，须大于零，用于检测并发修改。
     * @param content 要保存的草稿内容，允许暂时留空部分字段。
     * @param meta 写命令的幂等键和追踪标识。
     * @return 保存结果对应的资源版本和草稿修订号等回执信息。
     */
    CommandReceipt<Receipt> saveDraft(TrustedActor actor, Scope scope, String id, long expectedRevision,
                                      Content content, CommandMeta meta);

    /**
     * 校验草稿内容以及同一修订的成功试用记录，将当前草稿冻结为新的发布版本。
     *
     * @param actor 发起发布的可信主体，须具备该助手的发布权限。
     * @param scope 助手所属的租户和工作空间。
     * @param id 要发布的助手资源标识。
     * @param expectedVersion 调用方读取到的资源乐观锁版本，须大于或等于零。
     * @param expectedRevision 要发布的草稿修订号，须大于零且仍为当前修订。
     * @param trialId 用作发布凭据的成功试用记录标识，须对应同一助手、草稿修订和内容。
     * @param meta 写命令的幂等键和追踪标识。
     * @return 包含新发布版本的精确聊天配置引用的命令回执。
     */
    CommandReceipt<Receipt> publish(TrustedActor actor, Scope scope, String id, long expectedVersion,
                                    long expectedRevision, String trialId, CommandMeta meta);

    /**
     * 按预期资源版本停用助手，保留草稿和发布历史，阻止后续选用和执行。
     *
     * @param actor 发起停用的可信主体，须具备该助手的停用权限。
     * @param scope 助手所属的租户和工作空间。
     * @param id 要停用的助手资源标识。
     * @param expectedVersion 调用方读取到的资源乐观锁版本，须大于或等于零。
     * @param meta 写命令的幂等键和追踪标识。
     * @return 停用操作对应的助手命令回执。
     */
    CommandReceipt<Receipt> disable(TrustedActor actor, Scope scope, String id, long expectedVersion, CommandMeta meta);

    /**
     * 查询管理界面应向当前主体展示的操作资格，提交具体操作时仍需校验权限和业务条件。
     *
     * @param actor 要查询操作资格的可信主体。
     * @param scope 目标租户和工作空间。
     * @param id 目标助手的资源标识，为空时仅查询工作空间的创建资格。
     * @return 创建、编辑、试用、发布和停用的操作资格。
     */
    Capabilities capabilities(TrustedActor actor, Scope scope, String id);

    /**
     * 查询助手管理详情，读取资源状态、当前草稿及最近一次发布采用的草稿修订号。
     *
     * @param actor 发起查询的可信主体，须具备该助手的读取权限。
     * @param scope 助手所属的租户和工作空间。
     * @param id 要查询的助手资源标识。
     * @return 助手的管理详情。
     */
    Detail get(TrustedActor actor, Scope scope, String id);

    /**
     * 按助手标识升序分页查询当前主体可读取的助手管理条目，名称过滤基于当前草稿。
     *
     * @param actor 发起查询的可信主体。
     * @param scope 要查询的租户和工作空间。
     * @param afterId 上一页返回的助手标识游标，查询首页时为空。
     * @param name 可选的名称片段，匹配时忽略大小写，去除首尾空白后最多 80 个 Unicode 码点。
     * @param limit 本页最多返回的条目数，范围为 1 至 100。
     * @return 管理目录条目和下一页游标。
     */
    Page<Managed> managed(TrustedActor actor, Scope scope, String afterId, String name, int limit);

    /**
     * 按助手标识升序分页查询当前主体可选用的已发布助手，名称过滤基于当前发布版本。
     *
     * @param actor 发起查询的可信主体。
     * @param scope 要查询的租户和工作空间。
     * @param afterId 上一页返回的助手标识游标，查询首页时为空。
     * @param name 可选的名称片段，匹配时忽略大小写，去除首尾空白后最多 80 个 Unicode 码点。
     * @param limit 本页最多返回的条目数，范围为 1 至 100。
     * @return 当前可用的发布版本摘要和下一页游标。
     */
    Page<Summary> usable(TrustedActor actor, Scope scope, String afterId, String name, int limit);

    /**
     * 按发布版本号升序分页查询指定助手的发布历史。
     *
     * @param actor 发起查询的可信主体，须具备该助手的读取权限。
     * @param scope 助手所属的租户和工作空间。
     * @param id 要查询的助手资源标识。
     * @param afterVersion 上一页最后一个发布版本号，查询从其后的版本开始，查询首页时传零。
     * @param limit 本页最多返回的版本数，范围为 1 至 100。
     * @return 已发布版本及下一页游标。
     */
    Page<Release> versions(TrustedActor actor, Scope scope, String id, long afterVersion, int limit);

    /**
     * 按精确聊天配置引用解析发布快照，并校验当前主体的助手使用资格和资源状态。
     *
     * @param actor 发起解析的可信主体，须具备该助手的使用权限。
     * @param scope 助手所属的租户和工作空间。
     * @param profile 类型为 {@code chat-profile} 的配置引用，包含稳定配置标识及正整数发布版本号。
     * @param newConversation 为 {@code true} 时要求引用当前发布版本，为 {@code false} 时可解析已有会话固定的历史版本。
     * @return 引用指定的不可变发布快照。
     */
    Release resolvePublished(TrustedActor actor, Scope scope, DefinitionRef profile, boolean newConversation);
}
