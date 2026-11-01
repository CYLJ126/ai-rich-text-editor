package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 个人模型配置准备流程使用的不可变数据对象，集中表达草稿、凭据、安全视图及验证状态。
 * <p>
 * 稳定模型资源身份、草稿版本和凭据版本分别维护；保存草稿或验证成功均不发布可执行定义。
 * 内部 Credential 和 Probe 包含密文或短暂明文，只能由服务端存储／验证流程使用；公开响应使用脱敏视图。
 * 这些值对象只描述事实及字段约束，不替代应用层的当前身份、成员、资源和凭据用途授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public final class ModelDrafts {

    private ModelDrafts() {
    }

    private static void scopeMatches(ResourceRef resource, Scope scope) {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(scope);
        PlatformChecks.require(resource.type().value().equals("model") && resource.tenantId().equals(scope.tenantId()), "Invalid model scope");
    }

    /**
     * 用户在受控连接模板上填写的模型配置，不接受任意网络地址或平台密钥引用。
     * 容量字段只经过本地范围检查，不代表已验证供应商实际支持的能力。
     *
     * @param connectionTemplate  审核连接模板的精确 connection 引用，含固定版本
     * @param remoteModel         供应商接口使用的远端模型标识，不是本地稳定模型资源 ID
     * @param contextWindowTokens 声明的上下文窗口上限，范围为 1～10000000 个 token
     * @param maxOutputTokens     声明的单次最大输出 token 数，须为正数且不超过上下文窗口
     */
    public record Settings(DefinitionRef connectionTemplate, String remoteModel, long contextWindowTokens,
                           int maxOutputTokens) {
        public Settings {
            Objects.requireNonNull(connectionTemplate).requireType("connection");
            PlatformChecks.id(remoteModel, "remoteModel");
            PlatformChecks.require(remoteModel.length() <= 256 && remoteModel.chars().noneMatch(Character::isISOControl), "Invalid remote model");
            PlatformChecks.require(contextWindowTokens >= 1 && contextWindowTokens <= 10_000_000
                    && maxOutputTokens >= 1 && maxOutputTokens <= contextWindowTokens, "Invalid token limits");
        }
    }

    /**
     * 某一稳定模型资源当前待验证、待发布的配置草稿，与已发布版本独立。
     *
     * @param resource  草稿关联的稳定模型资源引用，资源类型为 model
     * @param scope     资源所属的租户和主空间范围，租户须与资源引用一致
     * @param name      草稿配置的展示名称，不直接改写已有模型资源名称
     * @param settings  用户选择的审核模板、远端模型及容量设置
     * @param version   草稿乐观锁版本；持久化时首次为 1，后续更新递增
     * @param updatedAt 该草稿版本的业务更新时间
     */
    public record Draft(ResourceRef resource, Scope scope, String name, Settings settings, long version,
                        Instant updatedAt) {
        public Draft {
            scopeMatches(resource, scope);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(settings);
            name = PlatformChecks.name(name);
            PlatformChecks.version(version, "version");
            Objects.requireNonNull(updatedAt);
        }
    }

    /**
     * 仅供内部存储和受控解密的个人凭据记录，不得作为命令回执或公开视图返回。
     * 凭据维护责任不随模型分享或空间治理角色自动转交；撤销清除当前密文，保留资源及历史关联。
     *
     * @param resource             凭据关联的稳定模型资源引用
     * @param scope                凭据登记时的租户和主空间范围
     * @param secret               服务端生成的独立 secret 引用，不包含明文密钥
     * @param responsibleSubjectId 凭据提供者的稳定主体 ID，用于独立核验维护责任
     * @param keyId                加密密钥环中的密钥标识，解密时按此精确取钥，撤销后可保留该历史标识
     * @param ciphertext           带算法及格式版本的认证密文封装；已撤销时必须为空，不保存明文
     * @param revoked              是否已撤销，撤销记录不能继续提供解密及验证用途
     * @param version              凭据乐观锁及业务代次；首次持久化为 1，替换或首次撤销时递增
     * @param updatedAt            当前凭据版本的业务更新时间
     * @param kind                 PERSONAL 仅本人使用；TENANT 额外要求精确代次的独立用途授权
     */
    public enum CredentialKind {PERSONAL, TENANT}

    public record Credential(ResourceRef resource, Scope scope, DefinitionRef secret, String responsibleSubjectId,
                             String keyId, String ciphertext, boolean revoked, long version, Instant updatedAt,
                             CredentialKind kind) {
        public Credential(ResourceRef resource, Scope scope, DefinitionRef secret, String responsibleSubjectId,
                          String keyId, String ciphertext, boolean revoked, long version, Instant updatedAt) {
            this(resource, scope, secret, responsibleSubjectId, keyId, ciphertext, revoked, version, updatedAt, CredentialKind.PERSONAL);
        }
        public Credential {
            kind = kind == null ? CredentialKind.PERSONAL : kind;
            scopeMatches(resource, scope);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(secret).requireType("secret");
            PlatformChecks.id(responsibleSubjectId, "responsibleSubjectId");
            PlatformChecks.require(revoked ? ciphertext == null : keyId != null && ciphertext != null, "Invalid credential state");
            PlatformChecks.version(version, "version");
            Objects.requireNonNull(updatedAt);
        }

        @Override
        public @NonNull String toString() {
            return "Credential[REDACTED]";
        }
    }

    /**
     * 可公开返回的凭据状态，不包含密文、密钥标识或责任主体详情。
     *
     * @param resourceId  凭据对应的稳定模型资源 ID
     * @param state       脱敏状态：MISSING 未提供、CONFIGURED 已配置、REVOKED 已撤销
     * @param version     当前或回执对应的凭据版本；尚未提供时为 0
     * @param mayMaintain 当前操作者是否匹配已登记责任主体；只是维护提示，不替代实际操作的身份及权限复核
     * @param kind        安全凭据类型，不包含密钥、密文或内部成员代次
     */
    public record CredentialView(String resourceId, String state, long version, boolean mayMaintain, String kind) {
        public CredentialView {
            kind = kind == null ? CredentialKind.PERSONAL.name() : kind;
        }

        public CredentialView(String resourceId, String state, long version, boolean mayMaintain) {
            this(resourceId, state, version, mayMaintain, CredentialKind.PERSONAL.name());
        }
    }

    /**
     * 配置查询的组合返回值，将待发布配置与凭据状态分开表达。
     *
     * @param draft      当前配置草稿；模型身份已存在但尚未保存配置时为空
     * @param credential 脱敏凭据状态，未提供凭据时返回 MISSING 状态而不是密钥内容
     */
    public record DraftView(Draft draft, CredentialView credential) {
    }

    /**
     * 连接模板选择器可使用的安全元数据，不能据此读取连接地址或认领平台凭据。
     *
     * @param reference  可选择的精确 connection 模板引用
     * @param providerId 审核模板登记的供应商标识
     * @param protocol   模板支持的精确 protocol 引用
     */
    public record TemplateView(DefinitionRef reference, String providerId, DefinitionRef protocol) {
    }

    /**
     * 有界候选扫描后的模型管理列表，不返回未授权资源总数。
     *
     * @param records    本批经当前 model:read 授权过滤的不可变资源列表，允许为空
     * @param nextCursor 绑定当前主体及空间的后续候选游标；可与空列表同时出现，为空时本批已无后续候选
     */
    public record ModelList(List<ModelResource> records, String nextCursor) {
        public ModelList {
            records = List.copyOf(records);
        }
    }

    /**
     * 验证请求处理状态；成功仅表示声明的探测检查通过，不代表发布或获得模型调用权。
     */
    public enum ValidationState {
        // 已受理，尚未领取；允许被一位调用者原子领取。
        PENDING,
        // 已领取，重试不能再次发起同一探测。
        RUNNING,
        // 本次受控认证及模型列表检查通过。
        SUCCEEDED,
        // 本次探测失败，具体原因以安全结果码表达。
        FAILED,
        // 未完成请求超时，或草稿、凭据、模板等依赖已变化，结果不能继续作为当前依据。
        STALE
    }

    /**
     * 可恢复的验证请求及状态记录，绑定受理时的配置、凭据和模板依据，不包含网络响应或密钥。
     *
     * @param id                服务端生成的验证请求 ID，不是访问令牌或聊天执行 ID
     * @param resource          待验证的稳定模型资源引用
     * @param scope             原验证请求所属的租户和空间范围
     * @param requestedBy       发起验证的稳定主体 ID，查询和领取须匹配此主体并重检当前授权
     * @param draftVersion      本次绑定的草稿版本，变化后原请求或结果失效
     * @param credentialVersion 本次绑定的凭据版本，替换或撤销后原请求或结果失效
     * @param templateDigest    受理时审核连接模板的确定性摘要，防止同引用内容变化被忽略
     * @param state             当前处理状态：待领取、运行、成功、失败或失效
     * @param code              过程状态或适配器返回的安全结果码，不包含远端异常详情
     * @param expiresAt         请求的处理截止时间，不是成功结果的供应商能力保证期
     * @param checkedAt         最近一次领取、完成或失效状态推进的业务时间；初始 PENDING 时为空
     * @param version           验证记录自身的乐观锁版本，初始为 0；与草稿及凭据版本独立
     */
    public record Validation(String id, ResourceRef resource, Scope scope, String requestedBy, long draftVersion,
                             long credentialVersion, String templateDigest, ValidationState state, String code,
                             Instant expiresAt, Instant checkedAt, long version) {
        public Validation {
            scopeMatches(resource, scope);
            PlatformChecks.id(id, "validationId");
            PlatformChecks.id(requestedBy, "requestedBy");
            PlatformChecks.require(draftVersion >= 1 && credentialVersion >= 1 && templateDigest != null && templateDigest.matches("[a-f0-9]{64}"), "Invalid validation binding");
            Objects.requireNonNull(state);
            Objects.requireNonNull(code);
            Objects.requireNonNull(expiresAt);
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 领取验证后短暂使用的服务端敏感输入，只交给受控网络适配器，不得序列化、记录日志或写入回执。
     *
     * @param connection  当前经授权选择的审核连接模板，不包含平台凭据引用
     * @param remoteModel 本次将在供应商模型列表中检查的模型标识
     * @param token       为本次验证解密的个人访问密钥，只允许在服务端内存中短暂使用
     */
    public record Probe(ConnectionDefinition connection, String remoteModel, String token) {
        @Override
        public @NonNull String toString() {
            return "ModelProbe[REDACTED]";
        }
    }

    /**
     * 网络适配器返回的安全探测结果，不携带远端正文或敏感异常。
     *
     * @param valid 是否通过当前声明的检查，仅当 code 为 MODEL_FOUND 时为 true
     * @param code  安全结果码：MODEL_FOUND、AUTHENTICATION_FAILED、MODEL_NOT_FOUND、REMOTE_UNAVAILABLE 或 INVALID_RESPONSE
     */
    public record ProbeResult(boolean valid, String code) {
        public ProbeResult {
            if (!List.of("MODEL_FOUND", "AUTHENTICATION_FAILED", "MODEL_NOT_FOUND", "REMOTE_UNAVAILABLE", "INVALID_RESPONSE").contains(code)
                    || valid != code.equals("MODEL_FOUND"))
                throw new IllegalArgumentException("Invalid validation result");
        }
    }
}
