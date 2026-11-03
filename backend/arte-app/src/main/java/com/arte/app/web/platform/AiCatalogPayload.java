package com.arte.app.web.platform;

import com.arte.ainew.pojo.budget.Money;
import com.arte.app.platform.ai.AiControlData;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;

/**
 * 用于向管理端返回平台审核配置集合的只读响应对象。
 * <p>
 * 目录指按固定版本组织的审核方案、连接、费率和默认策略集合；不暴露 Grant、账户、SecretRef 或可注入的执行绑定。
 *
 * @param reviewedPlans 已发布的审核方案集合，作为实际模型的发布依据，不是聊天模型候选
 * @param connections   审核连接的安全视图集合，不包含凭据引用或密钥
 * @param rates         已发布的固定版本费率集合，金额使用十进制字符串
 * @param defaults      首次准备空间 AI 时采用的默认开关和费率额度上限
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 09:51 ✾
 */
public record AiCatalogPayload(List<AiModelPlanPayload.Plan> reviewedPlans,
                               List<AiConnectionPayload.Connection> connections,
                               List<Rate> rates, DefaultPolicy defaults) {
    /**
     * 默认开关与精确费率上限，独立于模型启用和用户账户。
     *
     * @param enabled  首次准备空间 AI 时采用的默认启用开关
     * @param rateCaps 按精确费率配置的默认预算批准上限集合，不表示已创建账户或已分配余额
     */
    public record DefaultPolicy(boolean enabled, List<BudgetCap> rateCaps) {
        public DefaultPolicy {
            rateCaps = List.copyOf(rateCaps);
        }
    }

    /**
     * 以十进制字符串和币种对外表示金额，避免浏览器浮点转换丢失精度。
     *
     * @param amount   不使用科学计数法的十进制金额字符串
     * @param currency 金额所属的 ISO 4217 币种代码，例如 CNY
     */
    public record Amount(String amount, String currency) {
        static Amount from(Money value) {
            return value == null ? null : new Amount(value.amount().toPlainString(), value.currency().getCurrencyCode());
        }
    }

    /**
     * 描述默认模板中某一精确费率对应的预算批准额度上限。
     *
     * @param rate  预算规则对应的固定版本费率引用
     * @param limit 默认批准的预算额度上限，不是调用单价或账户余额
     */
    public record BudgetCap(DefinitionRef rate, Amount limit) {
    }

    /**
     * 向管理端返回某一固定版本费率的模型 Token 单价或工具按次单价。
     *
     * @param definition       固定版本费率引用，用于关联审核方案和预算规则
     * @param inputPerMillion  每百万输入 Token 的价格，工具按次费率时为空
     * @param outputPerMillion 每百万输出 Token 的价格，工具按次费率时为空
     * @param toolPerCall      每次工具调用的价格，模型 Token 费率时为空
     */
    public record Rate(DefinitionRef definition, Amount inputPerMillion, Amount outputPerMillion, Amount toolPerCall) {
    }

    public static AiCatalogPayload from(AiControlData.CatalogView value) {
        return new AiCatalogPayload(AiModelPlanPayload.View.from(value).plans(),
                AiConnectionPayload.View.from(value, List.of()).connections(), value.catalog().rates().stream().map(rate -> new Rate(rate.definition(), Amount.from(rate.inputPerMillion()),
                Amount.from(rate.outputPerMillion()), Amount.from(rate.toolPerCall()))).toList(),
                new DefaultPolicy(value.catalog().defaults().enabled(), value.catalog().defaults().budgets().stream()
                        .map(cap -> new BudgetCap(cap.rate(), Amount.from(cap.limit()))).toList()));
    }

    /**
     * 将审核目录快照及其修订号一并返回，供读取展示和后续写入时校验版本。
     *
     * @param version 全局目录修订号，用于并发更新校验，不是某个方案或费率的版本；未初始化时为 0
     * @param catalog 当前目录的安全响应内容，目录未初始化时为空
     */
    public record View(long version, AiCatalogPayload catalog) {
        public static View from(AiControlData.CatalogView value) {
            return new View(value.version(), value.catalog() == null ? null : AiCatalogPayload.from(value));
        }

        /**
         * 只转换命令公开值，保留操作身份与版本。
         */
        public static CommandReceipt<View> receipt(CommandReceipt<AiControlData.CatalogView> value) {
            return new CommandReceipt<>(value.operationId(), value.status(), value.delivery(), from(value.value()),
                    value.resultVersion(), value.acceptedAt(), value.completedAt());
        }
    }
}
