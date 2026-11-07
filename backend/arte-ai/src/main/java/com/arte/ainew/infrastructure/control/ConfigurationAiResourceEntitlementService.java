package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.*;
import com.arte.ainew.pojo.model.ModelAccessCandidate;
import com.arte.ainew.pojo.model.ModelAccessProof;
import com.arte.ainew.pojo.model.ModelPublications;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.serialize.CanonicalJson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;

/**
 * 单一配置来源的模型资格
 * <p>
 * 旧目录未迁移沿用 Grant，已迁移只取公共 ACL；个人命名空间始终需要独立公共证明，不回退 Grant。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public abstract class ConfigurationAiResourceEntitlementService implements AiResourceEntitlementService {

    private final ControlConfigurationReader reader;
    private final AdmissionAuthorization authorization;
    private final AiEntitlement.Source source = AiEntitlement.Source.DATABASE;
    private final ModelAclAccess modelAcl;

    protected ConfigurationAiResourceEntitlementService(ControlConfigurationReader reader, AdmissionAuthorization authorization) {
        this(reader, authorization, null);
    }

    protected ConfigurationAiResourceEntitlementService(ControlConfigurationReader reader, AdmissionAuthorization authorization,
                                                        ModelAclAccess modelAcl) {
        this.reader = Objects.requireNonNull(reader);
        this.authorization = Objects.requireNonNull(authorization);
        this.modelAcl = modelAcl;
    }

    public boolean usesModelAcl(ModelAclAccess expected) {
        return modelAcl == expected;
    }

    @Override
    public Mono<AiEntitlement> resolve(DefinitionRef binding, DefinitionRef capability, Action action, ExecutionContext context) {
        return Mono.defer(() -> {
            binding.requireType("binding");
            capability.requireType("capability");
            if (!MODEL_USE.equals(action)) {
                return Mono.error(unavailable());
            }
            return inSnapshot(context, configuration -> {
                // 模型候选
                List<ModelAccessCandidate> values = candidates(configuration, CapabilityDescriptor.Kind.GENERATION, true);
                return decision(context, values).map(checked -> {
                    // 确定模型
                    ModelAccessCandidate candidate = values.stream().filter(value -> value.binding().definition().equals(binding)
                                    && value.binding().capability().definition().equals(capability) && allowed(configuration, value, context, checked))
                            .findFirst().orElseThrow(ConfigurationAiResourceEntitlementService::unavailable);
                    // 将确定的候选模型组装成本次模型使用资格视图并返回
                    return entitlement(configuration, candidate, context, checked);
                });
            });
        });
    }

    @Override
    public Flux<AiEntitlement> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
        return Flux.defer(() -> {
            Objects.requireNonNull(kind);
            ContractChecks.range(limit, "limit", 1, 256);
            return inSnapshot(context, configuration -> {
                // 模型候选
                List<ModelAccessCandidate> values = candidates(configuration, kind);
                return decision(context, values).map(checked -> values.stream()
                        .filter(value -> allowed(configuration, value, context, checked))
                        .limit(limit)
                        .map(value -> entitlement(configuration, value, context, checked))
                        .toList());
            }).flatMapMany(Flux::fromIterable);
        });
    }

    @Override
    public void requireInCurrentTransaction(AiEntitlement expected, ExecutionContext context) {
        reader.inCurrentTransaction(context, configuration -> {
            // 检查当前配置仍覆盖原权限范围，且执行尚未超时。
            authorization.requireConfigurationScope(configuration, context, AdmissionAuthorization.INVOKE);
            // 校验配置定义及依赖，并查找与预检一致的可执行模型。
            ControlDefinitionChecks.validate(configuration);
            ModelAccessCandidate candidate = candidates(configuration, CapabilityDescriptor.Kind.GENERATION).stream()
                    .filter(value -> value.binding().equals(expected.binding())).findFirst()
                    .orElseThrow(ConfigurationAiResourceEntitlementService::unavailable);
            // 在原事务内重新取得当前模型 ACL 判定，未接入时沿用兼容模式。
            ModelAclAccess.Decision checked = modelAcl == null ? new ModelAclAccess.Decision(false, Map.of())
                    : modelAcl.checkCurrent(context, source, List.of(candidate));
            // 确认当前仍有模型使用权，且授权方式未在预检后发生切换。
            boolean protectedModel = checked.migrated() || checked.resourceBindings().contains(candidate.binding().definition());
            if (!allowed(configuration, candidate, context, checked) || protectedModel != (expected.modelAccess() != null)) {
                throw unavailable();
            }
            AiEntitlement current = entitlement(configuration, candidate, context, checked);
            // 确认执行归属、连接、发布标识及配置来源与预检一致。
            if (!current.executionOwner().equals(expected.executionOwner()) || !current.connection().equals(expected.connection())
                    || !current.releaseRef().equals(expected.releaseRef()) || current.source() != expected.source()) {
                throw unavailable();
            }
            // 已迁移时，确认模型资源、归属及定义来源与预检一致。
            if (protectedModel && (!current.modelAccess().resource().equals(expected.modelAccess().resource())
                    || !current.modelAccess().ownership().equals(expected.modelAccess().ownership())
                    || current.modelAccess().definitionSource() != expected.modelAccess().definitionSource())) {
                throw unavailable();
            }
            return null;
        });
    }

    /**
     * 在同一次可信配置快照中执行模型发现或精确解析的前置校验与业务回调。统一负责校验、准备配置和执行边界。
     * <p>
     * 数据库来源核验当前身份和空间资格，可复用本次订阅已建立的可信授权快照。
     * DATABASE 来源不重复调用身份解析器，由控制配置来源或已建立的可信快照承担身份及空间资格核验。
     * 随后通过 reader 取得或复用与执行上下文、配置来源匹配的配置，并在回调的 Reactor Context 中共享，
     * 避免嵌套读取在同次操作中混用不同配置；这里不保证每次都重新查询数据库。
     * <p>
     * 执行 work 前，确认上下文未过期、匹配主体／租户／空间的有效 Grant 覆盖原权限上限及 ai:invoke，
     * 并校验配置定义唯一性、能力契约和绑定／连接／费率／预算依赖。
     * 具体模型的旧 Grant 绑定资格或公共 ACL 判定由 work 内的后续流程完成；本方法本身不授予 model:use，
     * 也不建立原子写事务。实际受理／派发仍须调用 requireInCurrentTransaction 按当前事实复核。
     *
     * @param context 原执行上下文，保留其主体、范围、权限上限及截止时间
     * @param work    使用已通过前置校验的同次配置执行的响应式业务回调
     * @param <T>     业务回调的结果类型
     * @return 订阅后执行校验及业务回调的结果流；任一步失败即传播错误，不继续执行后续步骤
     */
    private <T> Mono<T> inSnapshot(ExecutionContext context, Function<ControlConfiguration, Mono<T>> work) {
        // 通过 reader 取得并共享配置：读取或复用本次配置，通过 Reactor Context 供嵌套调用使用，不保证每次重新查询数据库。
        return reader.inSnapshot(context, configuration -> {
            // 校验范围与定义：检查截止时间、Grant 是否覆盖原权限，以及能力、绑定、连接、费率和预算依赖。
            authorization.requireConfigurationScope(configuration, context, AdmissionAuthorization.INVOKE);
            ControlDefinitionChecks.validate(configuration);
            // 执行 work：继续模型发现或解析；任何一步失败都会中断流程。
            return work.apply(configuration);
        });
    }

    private List<ModelAccessCandidate> candidates(ControlConfiguration configuration, CapabilityDescriptor.Kind kind) {
        return candidates(configuration, kind, false);
    }

    /**
     * 从同次可信配置中组装指定能力类型的模型候选，供后续资格判定使用。
     * <p>
     * 先按能力类型筛选，并按绑定 ID、版本字符串排序；再逐项查找绑定引用的精确连接和费率，
     * 将费率转换为独立定义，与绑定、连接及发布标识组成候选。缺少连接或费率依赖时直接拒绝。
     * includeDisabled 为 false 时排除不可执行能力及未启用连接，用于发现和事务内复核；
     * 为 true 时保留这些候选，供精确解析在资格筛选后明确报告停用状态，不表示允许执行。
     * 本方法不按旧 Grant 或公共 ACL 过滤，返回候选不等于获得模型使用资格。
     *
     * @param configuration   已通过范围及定义校验的同次配置
     * @param kind            需要筛选的能力类型
     * @param includeDisabled 是否保留能力不可执行或连接未启用的候选
     * @return 按绑定 ID 和版本字符串排序、包含精确依赖快照的不可变候选列表
     * @throws AdmissionException 绑定所需的连接或费率定义缺失
     */
    private List<ModelAccessCandidate> candidates(ControlConfiguration configuration, CapabilityDescriptor.Kind kind, boolean includeDisabled) {
        return configuration.bindings().stream().filter(binding -> binding.capability().kind() == kind
                        && (includeDisabled || binding.capability().availability() == CapabilityDescriptor.Availability.EXECUTABLE))
                .sorted(Comparator.comparing((ResolvedBinding binding) -> binding.definition().id()).thenComparing(binding -> binding.definition().version()))
                .map(binding -> {
                    ConnectionDefinition connection = configuration.connections().stream().filter(value -> value.definition().equals(binding.connection()))
                            .findFirst().orElseThrow(ConfigurationAiResourceEntitlementService::unavailable);
                    ControlConfiguration.Rate rate = configuration.rates().stream().filter(value -> value.definition().equals(binding.rate()))
                            .findFirst().orElseThrow(ConfigurationAiResourceEntitlementService::unavailable);
                    return new ModelAccessCandidate(binding, connection, new RateDefinition(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall()), configuration.releaseRef());
                }).filter(value -> includeDisabled || value.connection().state() == ConnectionDefinition.State.ENABLED).toList();
    }

    /**
     * 取得本次候选集合的公共模型 ACL 判定，明确后续采用公共 ACL 还是兼容授权。
     * <p>
     * 已装配宿主授权桥时，传入原执行上下文、当前单一配置来源及完整候选集合，
     * 由宿主核验空间迁移状态、精确资源映射和公共资格，并返回允许的精确绑定及其证明。
     * 未装配授权桥时返回未迁移标记和空证明集合，交由 allowed 使用旧 Grant 判断；
     * 宿主须保证已迁移部署不会缺失该桥。授权桥返回错误时直接传播，不回退旧 Grant。
     *
     * @param context    原执行上下文，用于确定调用主体及租户／空间范围
     * @param candidates 同次可信配置中组装的候选集合，尚未按用户模型权限过滤
     * @return 包含迁移状态及精确绑定允许证明的异步判定；空允许集合本身不表示未迁移
     */
    private Mono<ModelAclAccess.Decision> decision(ExecutionContext context, List<ModelAccessCandidate> candidates) {
        return modelAcl == null ? Mono.just(new ModelAclAccess.Decision(false, Map.of())) : modelAcl.check(context, source, candidates);
    }

    /**
     * 将已通过资格筛选的单个候选组装成本次模型使用资格视图。
     * <p>
     * 首先要求能力可执行且连接已启用；再按候选的精确绑定读取公共 ACL 证明。
     * 有证明时，以资源引用、公共授权版本和空间清单版本生成资格依据摘要；无证明时沿用同次配置中匹配原调用主体／范围的 Grant 引用。
     * 返回值保留原执行归属、精确绑定、连接、发布标识及当前配置来源，仅声明 model:use。
     * 调用方必须先通过 allowed；本方法不独立重查 ACL，也不授予预算或凭据读取权限，返回视图不能替代后续事务内及外发前复核。
     *
     * @param configuration 已通过前置校验的同次配置，用于发布标识及兼容 Grant 引用
     * @param candidate     已通过 allowed 筛选的精确模型候选
     * @param context       原执行上下文，用于保留调用主体及执行租户／空间归属
     * @param decision      与本次候选及上下文对应的资格判定
     * @return 仅包含本次 model:use 资格及可选公共 ACL 证明的内存视图
     * @throws AdmissionException 候选能力不可执行或连接未启用
     */
    private AiEntitlement entitlement(ControlConfiguration configuration, ModelAccessCandidate candidate, ExecutionContext context, ModelAclAccess.Decision decision) {
        if (candidate.binding().capability().availability() != CapabilityDescriptor.Availability.EXECUTABLE
                || candidate.connection().state() != ConnectionDefinition.State.ENABLED) {
            throw new AdmissionException(ResultCodeEnum.AI_CAPABILITY_DISABLED);
        }
        // 本次公共模型资格依据
        ModelAccessProof proof = decision.allowed().get(candidate.binding().definition());
        String grant;
        if (proof == null) {
            grant = authorization.grant(configuration, context).grantRef();
        } else {
            String digest = CanonicalJson.digest(CanonicalJson.fields("resource", proof.resource(), "versions",
                    proof.versions(), "scopeVersion", proof.scopeVersion()));
            grant = "model-acl:" + digest;
        }
        return new AiEntitlement(ExecutionOwners.from(context), candidate.binding(), candidate.connection(),
                configuration.releaseRef(), grant, source, Set.of(MODEL_USE), proof);
    }

    /**
     * 按本次判定选定的授权规则，判断单个候选是否具备模型使用资格。
     * <p>
     * 已迁移范围只接受 decision 中存在该精确 bindingRef 的公共允许证明；
     * 即使旧 Grant 包含绑定 ID，也不能覆盖公共 ACL 拒绝。
     * 未迁移范围保留兼容语义，检查同次配置中匹配调用主体／范围的 Grant 是否包含绑定 ID，
     * 此处按 ID 族匹配而非精确版本匹配；实际候选版本仍受当前来源配置限制。
     * 本方法只解释已取得的配置及判定，不重新查询权威事实或检查能力／连接启用状态。
     *
     * @param configuration 已核验范围的同次配置，仅在未迁移时用于读取旧 Grant
     * @param candidate     本次待筛选的精确模型候选
     * @param context       原执行上下文，用于匹配兼容 Grant 的主体及租户／空间
     * @param decision      决定采用公共 ACL 或兼容 Grant 的本次资格判定
     * @return 当前选定的授权规则是否允许该候选；不表示已通过全部执行门控
     */
    private boolean allowed(ControlConfiguration configuration, ModelAccessCandidate candidate, ExecutionContext context, ModelAclAccess.Decision decision) {
        if (decision.migrated() || decision.resourceBindings().contains(candidate.binding().definition())
                || ModelPublications.isResourceBinding(candidate.binding().definition())) {
            return decision.allowed().containsKey(candidate.binding().definition());
        }
        return authorization.grant(configuration, context).bindingIds().contains(candidate.binding().definition().id());
    }

    /**
     * 创建模型配置不可用的统一业务异常，供引用查找、依赖解析或资格复核失败时使用。
     * 不存在、未获资格或配置不一致等路径使用相同错误码，避免在这些路径暴露内部判断细节。
     * 本方法仅创建异常，是否抛出由调用方决定；明确停用的候选由 entitlement 使用单独错误码拒绝。
     *
     * @return 错误码为 AI_CONFIGURATION_NOT_AVAILABLE 的新业务异常
     */
    private static AdmissionException unavailable() {
        return new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
    }
}
