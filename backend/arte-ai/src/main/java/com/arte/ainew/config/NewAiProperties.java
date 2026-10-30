package com.arte.ainew.config;

import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 承载新 AI 模块的受信部署参数、主体授权及固定版本的能力、绑定、连接、费率和预算配置。
 * <p>
 * 默认关闭，不携带明文凭据，不在启动时写数据库。
 *
 * @param enabled                      新 AI 模块启用开关，默认关闭
 * @param dataSourceBean               AI 持久化使用的物理数据源 Bean 名称，默认 appDataSource
 * @param releaseRef                   当前配置的发布标识，启用时必填，用于核对执行上下文的发布依赖
 * @param persistence                  数据库任务调度器的线程及排队配置，默认 4 个线程、排队容量 256
 * @param limits                       输入、输出、超时及输入快照有效期限制，也是执行政策的部署上限
 * @param grants                       主体在指定租户／空间内的操作权限、模型绑定和预算使用授权列表
 * @param capabilities                 固定版本的能力描述列表，声明输入／输出契约、特性及可执行状态
 * @param bindings                     固定版本的模型绑定列表，关联能力、连接、远端模型、上下文容量及费率
 * @param connections                  固定版本的连接定义列表，包含协议、地址、凭据引用及连接限制
 * @param rates                        固定版本的模型 Token 或工具按次费率列表
 * @param budgets                      按原执行归属配置的预算定义列表，包含额度、币种和关联费率
 * @param configurationMode            配置来源模式：FIXED 使用固定配置，DATABASE 使用受信数据库控制面；默认 FIXED
 * @param conversationMetadataBackfill 会话目录历史元数据回填开关配置，默认关闭
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new", ignoreUnknownFields = false)
public record NewAiProperties(boolean enabled, String dataSourceBean, String releaseRef,
                              Persistence persistence, Limits limits, List<Grant> grants,
                              List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                              List<ConnectionDefinition> connections, List<Rate> rates, List<Budget> budgets,
                              String configurationMode, ConversationMetadataBackfill conversationMetadataBackfill) {

    public NewAiProperties(boolean enabled, String dataSourceBean, String releaseRef, Persistence persistence, Limits limits,
                           List<Grant> grants, List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                           List<ConnectionDefinition> connections, List<Rate> rates, List<Budget> budgets) {
        this(enabled, dataSourceBean, releaseRef, persistence, limits, grants, capabilities, bindings, connections, rates, budgets, "FIXED");
    }

    public NewAiProperties(boolean enabled, String dataSourceBean, String releaseRef, Persistence persistence, Limits limits,
                           List<Grant> grants, List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                           List<ConnectionDefinition> connections, List<Rate> rates, List<Budget> budgets,
                           String configurationMode) {
        this(enabled, dataSourceBean, releaseRef, persistence, limits, grants, capabilities, bindings, connections, rates, budgets,
                configurationMode, null);
    }

    /**
     * 控制应用就绪后是否启动会话目录历史元数据回填，须在完成表结构迁移后由运维显式开启。
     *
     * @param enabled 是否启用历史元数据回填，默认关闭；开启后执行有界、可恢复的数据回填，不执行 DDL
     */
    public record ConversationMetadataBackfill(boolean enabled) {
    }

    /**
     * 配置 AI 数据库阻塞任务使用的有界调度器容量。
     *
     * @param threads     数据库调度器最多创建的线程数，默认 4
     * @param queuedTasks 数据库调度器每个底层线程允许排队的任务数上限，默认 256
     */
    public record Persistence(int threads, int queuedTasks) {
        public Persistence {
            ContractChecks.range(threads, "threads", 1, 64);
            ContractChecks.range(queuedTasks, "queuedTasks", 1, 10_000);
        }
    }

    /**
     * 配置 AI 执行的输入、输出、超时及输入快照有效期上限。
     *
     * @param maxInputBytes     最大输入字节数，默认 65,536
     * @param maxOutputTokens   单次模型调用允许的最大输出 Token 数，默认 4,096
     * @param maxOutputBytes    单次执行允许的最大输出字节数，默认 1,048,576
     * @param maximumTimeout    单次执行的最大超时时长，默认 2 分钟
     * @param snapshotRetention 输入快照有效时长，默认 15 分钟，不能短于 maximumTimeout；到期禁止新调用，不表示立即删除数据
     */
    public record Limits(int maxInputBytes, int maxOutputTokens, long maxOutputBytes,
                         Duration maximumTimeout, Duration snapshotRetention) {
        public Limits {
            ContractChecks.range(maxInputBytes, "maxInputBytes", 1, 1_000_000);
            ContractChecks.range(maxOutputTokens, "maxOutputTokens", 1, 1_000_000);
            ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, 32 * 1024 * 1024);
            ContractChecks.require(maximumTimeout != null && maximumTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                    && maximumTimeout.compareTo(Duration.ofHours(1)) <= 0, "Invalid maximum timeout");
            ContractChecks.require(snapshotRetention != null && snapshotRetention.compareTo(maximumTimeout) >= 0
                    && snapshotRetention.compareTo(Duration.ofDays(30)) <= 0, "Invalid snapshot retention");
        }
    }

    /**
     * 描述一个可信主体在指定租户／空间内可执行的操作及可使用的模型绑定和预算范围。
     *
     * @param subjectName   用于受信身份匹配的主体名称，用户主体通常为登录名
     * @param subjectId     主体的稳定标识，用于执行归属及身份一致性校验
     * @param principalKind 主体类型，例如 USER 或 SERVICE
     * @param tenantId      本授权适用的租户标识
     * @param workspaceId   本授权适用的工作空间标识
     * @param grantRef      本授权的依据／版本标识，用于记录和复核授权来源
     * @param enabled       本授权是否有效，停用后不允许据此取得操作资格
     * @param scopes        允许的操作权限集合，例如 ai:invoke、ai:read；请求权限必须包含于此集合
     * @param bindingIds    允许使用的模型绑定 ID 集合，此处保存 ID，精确版本仍由模型资格及来源配置核验
     * @param budgetRefs    允许访问或使用的预算引用集合，具体操作还须满足对应 scope 及预算归属要求
     */
    public record Grant(String subjectName, String subjectId, ExecutionPrincipal.Kind principalKind,
                        String tenantId, String workspaceId, String grantRef, boolean enabled,
                        Set<String> scopes, Set<String> bindingIds, Set<String> budgetRefs) {

        public Grant {
            ContractChecks.id(subjectName, "subjectName");
            ContractChecks.id(subjectId, "subjectId");
            Objects.requireNonNull(principalKind);
            new ExecutionOwner(tenantId, workspaceId, subjectId);
            ContractChecks.id(grantRef, "grantRef");
            scopes = ids(scopes, "scopes");
            bindingIds = ids(bindingIds, "bindingIds");
            budgetRefs = ids(budgetRefs, "budgetRefs");
        }

        private static Set<String> ids(Set<String> values, String field) {
            var copied = ContractChecks.set(values, field, 256);
            copied.forEach(value -> ContractChecks.id(value, field));
            return copied;
        }
    }

    /**
     * 定义固定版本的模型 Token 费率或工具按次费率，两种计价方式互斥。
     *
     * @param definition       固定版本的费率引用，类型必须为 rate
     * @param inputPerMillion  模型每百万输入 Token 的价格及币种；模型费率必填，工具费率为空
     * @param outputPerMillion 模型每百万输出 Token 的价格及币种，须与输入价格同币种；工具费率为空
     * @param toolPerCall      工具每次调用的价格及币种；非空时输入／输出 Token 价格必须为空，模型费率中此字段为空
     */
    public record Rate(DefinitionRef definition, Money inputPerMillion, Money outputPerMillion,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Money toolPerCall) {
        public Rate(DefinitionRef definition, Money inputPerMillion, Money outputPerMillion) {
            this(definition, inputPerMillion, outputPerMillion, null);
        }

        public java.util.Currency currency() {
            return toolPerCall == null ? inputPerMillion.currency() : toolPerCall.currency();
        }

        @ConstructorBinding
        public Rate {
            Objects.requireNonNull(definition).requireType("rate");
            if (toolPerCall == null) {
                Objects.requireNonNull(inputPerMillion);
                Objects.requireNonNull(outputPerMillion);
                ContractChecks.require(inputPerMillion.currency().equals(outputPerMillion.currency()), "Rate currency mismatch");
            } else
                ContractChecks.require(inputPerMillion == null && outputPerMillion == null, "Tool rate must not contain Token prices");
        }
    }

    /**
     * 定义指定执行归属下预算账户的配置额度及关联费率，供预算资格校验和账户初始化使用。
     *
     * @param budgetRef 预算定义的稳定引用，用于定位对应账户
     * @param owner     预算归属，包含租户、工作空间及稳定主体标识
     * @param limit     配置的预算额度及币种，不代表账户当前可用余额，也不用于重置已有账户
     * @param rate      关联的固定版本费率引用，类型必须为 rate，使用时须核对费率及币种兼容性
     */
    public record Budget(String budgetRef, ExecutionOwner owner, Money limit, DefinitionRef rate) {
        public Budget {
            ContractChecks.id(budgetRef, "budgetRef");
            Objects.requireNonNull(owner);
            Objects.requireNonNull(limit);
            Objects.requireNonNull(rate).requireType("rate");
        }
    }

    @ConstructorBinding
    public NewAiProperties {
        configurationMode = configurationMode == null ? "FIXED" : configurationMode;
        conversationMetadataBackfill = conversationMetadataBackfill == null ? new ConversationMetadataBackfill(false) : conversationMetadataBackfill;
        ContractChecks.require(Set.of("FIXED", "DATABASE").contains(configurationMode), "Unknown AI configuration mode");
        dataSourceBean = dataSourceBean == null ? "appDataSource" : ContractChecks.id(dataSourceBean, "dataSourceBean");
        persistence = persistence == null ? new Persistence(4, 256) : persistence;
        limits = limits == null ? new Limits(65_536, 4096, 1_048_576, Duration.ofMinutes(2), Duration.ofMinutes(15)) : limits;
        grants = copy(grants);
        capabilities = copy(capabilities);
        bindings = copy(bindings);
        connections = copy(connections);
        rates = copy(rates);
        budgets = copy(budgets);
        if (enabled) {
            ContractChecks.id(releaseRef, "releaseRef");
            if (configurationMode.equals("FIXED"))
                ContractChecks.require(!grants.isEmpty() && !capabilities.isEmpty() && !bindings.isEmpty()
                        && !connections.isEmpty() && !rates.isEmpty() && !budgets.isEmpty(), "Enabled admission requires complete fixed configuration");
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : ContractChecks.list(values, "configuration", 0, 256);
    }
}
