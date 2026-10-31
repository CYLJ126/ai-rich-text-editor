package com.arte.app.web.platform;

import com.arte.ainew.pojo.model.ModelDrafts.Settings;
import com.arte.ainew.pojo.model.ModelDrafts.Validation;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import jakarta.validation.constraints.*;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;

/**
 * 个人模型配置准备接口的 HTTP 请求和响应对象，负责公开字段约束及安全视图转换。
 * <p>
 * 请求只提供资源定位、期望版本和允许填写的配置，不接收客户端自报的地址、归属、主体、密钥环标识、SecretRef 或验证快照。
 * 请求记录通过 StrictPlatformRequest 拒绝未声明字段；Bean Validation 只检查字段形式，真实身份、成员、资源及凭据责任仍由应用层核验。
 * 凭据请求包含短暂明文，禁止日志输出；验证响应将原始受理回执与当前状态分别表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public final class ModelDraftPayload {

    private ModelDraftPayload() {
    }

    /**
     * 当前空间可读模型管理列表的有界查询请求。
     *
     * @param scope  待查询的租户和空间范围，不能代替服务端授权
     * @param cursor 上次返回的主体及空间绑定游标，首次查询可省略
     * @param size   本次最多返回的可读资源数，省略时为 20，允许范围为 1～100
     */
    public record ListRequest(@NotNull Scope scope, @Size(max = 129) String cursor,
                              @Min(1) @Max(100) Integer size) implements StrictPlatformRequest {
        public ListRequest {
            size = size == null ? 20 : size;
        }
    }

    /**
     * 保存模型配置草稿的请求，不发布可执行版本，也不直接修改既有发布。
     *
     * @param scope               模型所属的租户和主空间范围
     * @param resourceId          已创建的稳定模型资源 ID
     * @param expectedVersion     期望的当前草稿版本；首次保存为 0，不是模型管理或凭据版本
     * @param name                草稿配置的展示名称，最长 100 字符
     * @param connectionTemplate  审核连接模板的精确引用，不允许自报 endpoint 或平台密钥
     * @param remoteModel         供应商接口使用的远端模型标识，最长 256 字符
     * @param contextWindowTokens 声明的上下文窗口 token 上限，范围为 1～10000000
     * @param maxOutputTokens     声明的单次输出 token 上限，须为正数且由业务对象进一步校验不超过上下文窗口
     */
    public record SaveRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                              @NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 100) String name,
                              @NotNull DefinitionRef connectionTemplate, @NotBlank @Size(max = 256) String remoteModel,
                              @NotNull @Min(1) @Max(10000000) Long contextWindowTokens,
                              @NotNull @Min(1) Integer maxOutputTokens) implements StrictPlatformRequest {
        public Settings settings() {
            return new Settings(connectionTemplate, remoteModel, contextWindowTokens, maxOutputTokens);
        }
    }

    /**
     * 首次提供或替换个人访问密钥的请求；责任主体由服务端确定，不能通过请求转交。
     *
     * @param scope           模型所属的租户和主空间范围
     * @param resourceId      凭据关联的稳定模型资源 ID
     * @param expectedVersion 期望的当前凭据版本；首次提供为 0，与草稿版本独立
     * @param token           本次提供的单行访问密钥，最长 8192 字符；不得记录日志、回显或直接持久化
     */
    public record CredentialRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                    @NotNull @Min(0) Long expectedVersion,
                                    @NotBlank @Size(max = 8192) String token) implements StrictPlatformRequest {
        @Override
        public @NonNull String toString() {
            return "CredentialRequest[REDACTED]";
        }
    }

    /**
     * 原凭据责任主体撤销个人密钥的请求，不恢复模型读取或空间成员资格。
     *
     * @param scope           凭据登记时的租户和主空间范围
     * @param resourceId      凭据关联的稳定模型资源 ID
     * @param expectedVersion 期望的当前凭据版本，用于防止旧请求覆盖新凭据
     */
    public record RevokeRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                @NotNull @Min(0) Long expectedVersion) implements StrictPlatformRequest {
    }

    /**
     * 对指定草稿及凭据版本发起验证的请求，不允许客户端提交成功结论或网络目标。
     *
     * @param scope             模型所属的租户和主空间范围
     * @param resourceId        待验证的稳定模型资源 ID
     * @param draftVersion      本次期望验证的已保存草稿版本，须为正数且等于当前版本
     * @param credentialVersion 本次期望使用的凭据版本，须为正数且等于当前版本
     */
    public record ValidateRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                  @NotNull @Min(1) Long draftVersion,
                                  @NotNull @Min(1) Long credentialVersion) implements StrictPlatformRequest {
    }

    /**
     * 查询已有验证状态的请求，验证 ID 本身不授予访问权。
     *
     * @param scope        原验证请求的租户和空间范围
     * @param validationId 服务端受理时生成的验证请求 ID，仍核验原请求者及当前权限
     */
    public record ValidationRequest(@NotNull Scope scope,
                                    @NotBlank @Size(max = 256) String validationId) implements StrictPlatformRequest {
    }

    /**
     * 模型管理列表的公开结果，不暴露候选总数或未授权模型详情。
     *
     * @param records    本批操作者具有读取资格的安全模型元数据，可能少于请求数量或为空
     * @param nextCursor 后续候选游标；非空时可继续查询，即使本批 records 为空
     */
    public record ModelListView(List<ModelResourcePayload.Resource> records, String nextCursor) {
    }

    /**
     * 验证记录的公开视图，隐藏请求主体、模板摘要及内部乐观锁版本等服务端依据。
     *
     * @param validationId      服务端生成的验证请求 ID
     * @param resourceId        本次验证关联的稳定模型资源 ID
     * @param draftVersion      本次受理固定的草稿版本
     * @param credentialVersion 本次受理固定的凭据版本
     * @param state             处理状态：PENDING、RUNNING、SUCCEEDED、FAILED 或 STALE
     * @param code              过程状态或安全结果码，不包含供应商响应正文、密钥或异常详情
     * @param expiresAt         本次请求的处理期限，不是已完成结果的能力保证期
     * @param checkedAt         最近一次状态推进时间；刚受理、尚未领取时为空
     */
    public record ValidationView(String validationId, String resourceId, long draftVersion, long credentialVersion,
                                 String state, String code, Instant expiresAt, Instant checkedAt) {
        public static ValidationView from(Validation v) {
            return new ValidationView(v.id(), v.resource().resourceId(), v.draftVersion(), v.credentialVersion(),
                    v.state().name(), v.code(), v.expiresAt(), v.checkedAt());
        }
    }

    /**
     * 发起或确认验证后的组合响应，保留原始幂等受理证据，同时返回重新核验的当前状态。
     *
     * @param receipt 原始受理回执，重放时保留首次受理内容，不能将其历史 PENDING 状态视为当前状态
     * @param current 本次操作后独立查询或完成处理得到的当前验证视图
     */
    public record ValidationResponse(CommandReceipt<ValidationView> receipt, ValidationView current) {
    }
}
