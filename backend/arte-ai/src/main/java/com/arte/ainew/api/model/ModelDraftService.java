package com.arte.ainew.api.model;

import com.arte.ainew.pojo.model.ModelDrafts.*;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;

import java.util.List;

/**
 * 个人模型配置准备的应用接口，提供可读模型列表、连接模板、草稿、个人凭据维护及连接验证状态管理。
 * <p>
 * 同步方法应在宿主提供的数据库调度线程调用，由应用服务管理当前身份、公共授权、治理锁和本地事务。
 * 草稿保存、凭据变更和验证受理使用幂等命令；验证分为受理、领取和完成三个阶段，网络探测必须在领取事务提交后执行。
 * 本接口不发布模型、不修改预算，也不直接授予聊天执行资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public interface ModelDraftService {
    /**
     * 显式指定个人或租户凭据，不因模型分享自动转交用途。
     */
    default CommandReceipt<CredentialView> replaceCredential(TrustedActor actor, CommandMeta meta, Scope scope,
                                                             ResourceRef resource, long expectedVersion, String token, CredentialKind kind) {
        if (kind != CredentialKind.PERSONAL) {
            throw new com.arte.core.exception.platform.PlatformException(com.arte.core.enums.platform.PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "tenant-credential");
        }
        return replaceCredential(actor, meta, scope, resource, expectedVersion, token);
    }

    /**
     * 分批查询当前空间中操作者具有 model:read 权限的模型管理资源，不按创建者身份代替授权过滤。
     * 每次最多检查 100 个候选，可能返回空列表和后续游标；不提供隐藏资源的数量。
     *
     * @param actor  当前可信操作者，方法内仍重新核验身份及空间资格
     * @param scope  待查询的租户和主空间范围
     * @param cursor 上次返回且绑定当前主体及空间的游标；首次查询传 null
     * @param size   本次最多返回的可读资源数，范围为 1～100
     * @return 当前批次的可读资源及后续游标，游标为空表示本批已无后续候选
     */
    ModelList list(TrustedActor actor, Scope scope, String cursor, int size);

    /**
     * 查询经部署审核且同时满足当前租户、空间及出口政策的连接模板，只返回安全元数据。
     *
     * @param actor 当前可信操作者，须具有目标空间读取资格
     * @param scope 模板将被使用的租户和空间范围
     * @return 可选择的精确模板引用、供应商及协议，不含连接地址或平台凭据
     */
    List<TemplateView> templates(TrustedActor actor, Scope scope);

    /**
     * 在当前 model:read 授权下读取配置草稿及脱敏凭据状态，不解密或返回凭据内容。
     *
     * @param actor    当前可信操作者
     * @param scope    模型所属的租户和主空间范围
     * @param resource 稳定模型资源引用，必须与真实归属匹配
     * @return 当前草稿与凭据状态；模型尚未保存配置时 draft 为空
     */
    DraftView get(TrustedActor actor, Scope scope, ResourceRef resource);

    /**
     * 在当前 model:edit 授权下保存配置草稿，校验连接模板及版本后原子提交草稿、回执、审计和事件。
     * 保存不会覆盖已有模型发布；同键同字段重试返回原回执，仍重新检查当前资格。
     *
     * @param actor           当前可信操作者
     * @param meta            本次命令的幂等操作键及服务端追踪信息
     * @param scope           模型所属的租户和主空间范围
     * @param resource        已存在的稳定模型资源引用
     * @param expectedVersion 期望的当前草稿版本；首次保存为 0，不是模型管理版本
     * @param name            待发布配置的展示名称，不直接改写已有资源名称
     * @param settings        精确连接模板、远端模型标识及请求容量设置
     * @return 保存后的草稿和对应版本的命令回执
     */
    CommandReceipt<Draft> save(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource, long expectedVersion, String name, Settings settings);

    /**
     * 首次提供或替换个人凭据，首次提供时由服务端记录责任主体；后续替换还须是原责任主体。
     * 操作者须具有当前 model:edit 资格且政策允许，凭据加密保存，回执仅包含脱敏状态。
     *
     * @param actor           当前可信操作者，不能通过模型分享或治理角色取得他人的凭据维护资格
     * @param meta            本次命令的幂等操作键及服务端追踪信息
     * @param scope           模型所属的租户和主空间范围
     * @param resource        已保存配置草稿的稳定模型资源引用
     * @param expectedVersion 期望的当前凭据版本；首次提供为 0，与草稿版本独立
     * @param token           用户提交的单行访问密钥，仅用于本次加密，不得写入日志或回执
     * @return 脱敏凭据状态和新凭据版本的命令回执
     */
    CommandReceipt<CredentialView> replaceCredential(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource, long expectedVersion, String token);

    /**
     * 由原责任主体撤销个人凭据并清除当前密文，保留模型及历史记录。
     * 责任主体退出空间后仍可撤销，但必须是当前有效账号；此操作不恢复模型读取或其他空间权限。
     *
     * @param actor           当前可信凭据责任主体
     * @param meta            本次命令的幂等操作键及服务端追踪信息
     * @param scope           凭据登记时的租户和主空间范围
     * @param resource        凭据关联的稳定模型资源引用
     * @param expectedVersion 期望的当前凭据版本，首次撤销时递增，已撤销状态不重复推进
     * @return 仅含目标资源 ID、凭据状态、版本及本人维护标记的命令回执
     */
    CommandReceipt<CredentialView> revokeCredential(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource, long expectedVersion);

    /**
     * 登记待领取的连接验证请求，将当次草稿、凭据版本及审核模板摘要固定到同一记录中。
     * 要求当前 model:validate 资格和个人凭据责任，不在本方法中发送网络请求。
     *
     * @param actor             当前可信操作者，须是未撤销凭据的责任主体
     * @param meta              验证受理的幂等操作键及服务端追踪信息
     * @param scope             模型所属的租户和主空间范围
     * @param resource          待验证的稳定模型资源引用
     * @param draftVersion      本次期望验证的已保存草稿版本，必须等于当前版本
     * @param credentialVersion 本次期望使用的凭据版本，必须等于当前版本
     * @return 原始验证受理回执；回执中的状态不代替后续当前状态查询
     */
    CommandReceipt<Validation> requestValidation(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource, long draftVersion, long credentialVersion);

    /**
     * 原子领取仍有效的待执行验证，重新授权后解密本次探测所需凭据并将记录推进为 RUNNING。
     * 只有一位调用者取得探测输入；运行中、已结束或已失效请求不重复领取。调用者须在事务提交后再发起网络探测。
     *
     * @param actor        原验证请求的当前可信操作者
     * @param scope        原请求的租户和空间范围
     * @param validationId 服务端生成的验证请求 ID，本身不授予访问权
     * @return 本次唯一领取的服务端敏感探测输入；无法领取时为空，不得据此自动重发
     */
    java.util.Optional<Probe> claimValidation(TrustedActor actor, Scope scope, String validationId);

    /**
     * 提交探测结果并重新检查当前授权、期限及版本；依赖变化或处理超时则标记为 STALE。
     * 重复完成不会覆盖已有终态，不把验证成功视为发布或执行许可。
     *
     * @param actor        原验证请求的当前可信操作者
     * @param scope        原请求的租户和空间范围
     * @param validationId 已领取的验证请求 ID
     * @param result       受信验证适配器产生的安全结果，不接受客户端自报结果
     * @return 重新核验并持久化后的当前验证记录
     */
    Validation completeValidation(TrustedActor actor, Scope scope, String validationId, ProbeResult result);

    /**
     * 查询当前验证状态，必要时将超时未完成或依赖已变化的记录更新为 STALE，不触发网络探测。
     *
     * @param actor        原验证请求的当前可信操作者，仍须具有当前 model:validate 资格
     * @param scope        原请求的租户和空间范围
     * @param validationId 待查询的验证请求 ID，其他空间或其他请求者的记录不对外暴露
     * @return 当前验证记录，状态与当时的受理回执分别表达
     */
    Validation validation(TrustedActor actor, Scope scope, String validationId);
}
