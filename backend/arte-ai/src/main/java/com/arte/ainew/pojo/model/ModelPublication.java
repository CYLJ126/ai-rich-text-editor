package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.serialize.CanonicalJson;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.*;

/**
 * 经受信来源核对的模型精确发布内容及来源证据。
 * <p>
 * 保存生成能力绑定及其精确连接、模型费率依赖，供版本关联、内容摘要计算和历史追溯使用。
 * binding 必须为 GENERATION，连接及费率引用须与对应快照一致；只保存凭据引用，不包含密钥明文、预算账户或调用身份。
 * 该对象记录发布时的内容，不代替调用时的当前授权、停用状态、凭据用途及预算校验。
 *
 * @param source          内容权威来源：FIXED 为固定部署，DATABASE 为平台审核目录，RESOURCE 为已验证的实际模型资源发布，PERSONAL 仅为历史来源编码；不代表供应商或访问许可。
 * @param catalogRevision DATABASE 或 RESOURCE 采用的精确审核目录修订；FIXED 和历史 PERSONAL 为空，不解析 latest。
 * @param releaseRef      读取该发布内容时记录的发布标识，用于保留来源证据；独立于目录修订号、binding 字符串版本及管理资源版本。
 * @param binding         精确模型绑定快照，包含能力契约、远端连接引用、供应商实际模型标识、上下文容量及费率引用；不以其 ID 代替稳定管理资源 ID。
 * @param connection      与 binding 连接引用一致的连接快照，包含提供方、协议、目标地址、凭据引用、状态及传输限制；不返回凭据明文。
 * @param rate            与 binding 费率引用一致的模型 Token 费率快照；不允许工具按次费率，不表示已分配预算、可用余额或实际扣费结果。
 * @param planRef         RESOURCE 发布采用的固定审核方案，其他来源为空
 * @param credentialKind  RESOURCE 发布的固定凭据种类，其他来源为空
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public record ModelPublication(Source source, Long catalogRevision, String releaseRef, ResolvedBinding binding,
                               ConnectionDefinition connection, RateDefinition rate, DefinitionRef planRef,
                               ModelDrafts.CredentialKind credentialKind) {

    /**
     * FIXED 及历史来源快照无资源方案证据；RESOURCE 必须使用完整构造。
     */
    public ModelPublication(Source source, Long catalogRevision, String releaseRef, ResolvedBinding binding,
                            ConnectionDefinition connection, RateDefinition rate) {
        this(source, catalogRevision, releaseRef, binding, connection, rate, null, null);
    }

    /**
     * 发布内容来源；稳定存储编码独立于枚举声明顺序。
     */
    public enum Source {
        // 固定部署的不可变定义。
        FIXED(0),
        // 旧数据库目录定义，仅保留历史编码。
        DATABASE(1),
        // 旧个人发布，仅保留历史编码。
        PERSONAL(2),
        // 经草稿、凭据和验证发布的实际模型资源。
        RESOURCE(3);
        /**
         * 定义来源登记表使用的编码：0 为 FIXED，1 为 DATABASE，2 为 PERSONAL，3 为 RESOURCE。
         */
        private final int code;

        Source(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public ModelPublication {
        Objects.requireNonNull(source);
        if (source == Source.RESOURCE) {
            Objects.requireNonNull(planRef).requireType("model-plan");
            Objects.requireNonNull(credentialKind);
            PlatformChecks.require(!"latest".equalsIgnoreCase(planRef.version()), "Fixed plan required");
        } else {
            PlatformChecks.require(planRef == null && credentialKind == null, "Unexpected resource evidence");
        }
        Objects.requireNonNull(binding);
        Objects.requireNonNull(connection);
        Objects.requireNonNull(rate);
        PlatformChecks.id(releaseRef, "releaseRef");
        PlatformChecks.require((source == Source.DATABASE || source == Source.RESOURCE) ? catalogRevision != null && catalogRevision > 0 : catalogRevision == null, "Exact source revision required");
        PlatformChecks.require(binding.capability().kind() == CapabilityDescriptor.Kind.GENERATION
                && binding.connection().equals(connection.definition()) && Objects.equals(binding.rate(), rate.definition())
                && rate.toolPerCall() == null, "Not a complete published model");
    }

    /**
     * 集合显式排序，避免 Set 遍历顺序改变跨 JVM 内容摘要。
     */
    public Map<DefinitionRef, String> definitionDigests() {
        var c = binding.capability();
        var capability = CanonicalJson.fields("definition", c.definition(), "kind", c.kind(), "input", c.inputSchema(), "output", c.outputSchema(),
                "features", c.features().stream().map(Enum::name).sorted().toList(), "sideEffect", c.sideEffect(), "availability", c.availability());
        var result = new LinkedHashMap<DefinitionRef, String>();
        result.put(c.definition(), CanonicalJson.digest(capability));
        result.put(binding.definition(), CanonicalJson.digest(CanonicalJson.fields("definition", binding.definition(), "capability", capability,
                "target", binding.target(), "window", binding.contextWindowTokens(), "rate", binding.rate())));
        result.put(connection.definition(), CanonicalJson.digest(connection));
        result.put(rate.definition(), CanonicalJson.digest(rate));
        return Collections.unmodifiableMap(result);
    }

    public String contentDigest() {
        return CanonicalJson.digest(definitionDigests().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().type() + ":" + e.getKey().id() + ":" + e.getKey().version()))
                .map(e -> CanonicalJson.fields("definition", e.getKey(), "digest", e.getValue())).toList());
    }
}
