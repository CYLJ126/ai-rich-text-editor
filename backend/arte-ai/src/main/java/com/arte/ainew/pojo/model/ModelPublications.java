package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.time.Instant;
import java.util.Objects;

/**
 * 实际模型不可变发布的依赖证据及安全管理视图，不包含密钥、预算额度或执行身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class ModelPublications {

    private ModelPublications() {
    }

    /**
     * 判断服务端保留的资源绑定族，普通带 personal 前缀的旧目录名称不在此范围。
     */
    public static boolean isResourceBinding(DefinitionRef reference) {
        return reference.type().equals("binding") && reference.id().matches("resource-[a-f0-9]{64}");
    }

    /**
     * 资源发布附加证据，精确内容仍由 ModelResourceVersion 保存；发布后不得覆盖。
     *
     * @param resource          稳定模型资源引用
     * @param scope             固定管理空间；租户模型的执行空间由独立使用范围决定
     * @param binding           服务端分配的实际模型精确绑定
     * @param planRef           采用的精确 model-plan 审核方案，用于固定协议、能力及费率政策
     * @param template          原连接模板引用
     * @param templateDigest    验证时及发布时一致的连接模板摘要
     * @param planDigest        发布时采用的完整审核方案摘要
     * @param credentialKind    由资源归属固定的凭据种类
     * @param catalogRevision   审核来源的精确目录修订，不是实际模型版本
     * @param draftVersion      本次发布采用的草稿版本
     * @param credentialVersion 本次发布采用的凭据代次，轮换后旧发布停止新执行
     * @param secret            包含凭据代次的独立密钥引用，不包含明文
     * @param validationId      已成功且依赖一致的验证请求 ID
     * @param maxOutputTokens   本版本允许的输出 token 上限
     * @param publishedBy       发布者稳定主体 ID，不代替资源归属
     * @param publishedAt       发布业务时间
     */
    public record Release(ResourceRef resource, Scope scope, DefinitionRef binding, DefinitionRef planRef,
                          DefinitionRef template, String templateDigest, String planDigest,
                          ModelDrafts.CredentialKind credentialKind, long catalogRevision, long draftVersion,
                          long credentialVersion, DefinitionRef secret, String validationId, int maxOutputTokens,
                          String publishedBy, Instant publishedAt) {
        public Release {
            Objects.requireNonNull(resource);
            Objects.requireNonNull(scope);
            binding.requireType("binding");
            planRef.requireType("model-plan");
            template.requireType("connection");
            Objects.requireNonNull(credentialKind);
            if (planDigest == null || !planDigest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid plan digest");
            }
            secret.requireType("secret");
            if (!resource.tenantId().equals(scope.tenantId()) || catalogRevision < 1 || draftVersion < 1 || credentialVersion < 1
                    || maxOutputTokens < 1 || templateDigest == null || !templateDigest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid resource publication evidence");
            }
            Objects.requireNonNull(validationId);
            Objects.requireNonNull(publishedBy);
            Objects.requireNonNull(publishedAt);
        }
    }

    /**
     * 受信发布方案，用户只能选择已审核且与草稿连接和远端模型匹配的项目。
     *
     * @param planRef             审核方案的精确 model-plan 引用
     * @param rate                固定模型费率引用，不能由用户自报金额
     * @param catalogRevision     当前审核目录修订，提交时须再次比较
     * @param contextWindowTokens 审核模型允许的上下文上限
     */
    public record Option(DefinitionRef planRef, DefinitionRef rate, long catalogRevision,
                         long contextWindowTokens) {
    }

    /**
     * 一个精确资源发布的当前依赖状态，不表示空间已启用或获得聊天资格。
     *
     * @param binding         精确资源绑定引用
     * @param valid           当前资源及发布依赖是否有效，不表示空间启用或预算齐备
     * @param reason          安全状态码，不返回他人凭据或账本信息
     * @param maxOutputTokens 该发布的输出 token 上限
     */
    public record Status(DefinitionRef binding, boolean valid, String reason, int maxOutputTokens) {
    }
}
