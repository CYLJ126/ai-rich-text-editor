package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.budget.Money;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;
import java.util.Objects;

/**
 * 当前主体、执行空间及精确模型版本的使用准备状态
 * <p>
 * READY 不保证余额或供应商成功。
 * approvedLimit 来自受信空间规则，不是客户端申请额度，也不是账户余额。
 *
 * @param scope         实际执行的租户和工作空间，预算归属于该范围内的当前主体，不使用模型管理空间代替
 * @param resource      稳定模型资源引用，用于定位模型身份及核验当前资源使用权限
 * @param binding       本次检查或准备的精确模型绑定版本，不解析 latest 或自动升级已有发布
 * @param rate          该模型版本关联的精确费率，兼容预算须匹配相同费率标识和版本
 * @param state         当前准备状态，仅反映本次核验结果，不替代后续受理、派发及外发授权
 * @param reason        准备状态对应的安全原因码，用于提示可准备、缺少规则或其他阻塞条件
 * @param budgetRefs    当前主体在执行空间内可复用的兼容预算引用；尚未准备或被阻塞时可能为空
 * @param approvedLimit 受信空间规则批准的额度，无法取得适用规则时可为空，不表示账户余额或新增额度
 * @param allowedActions 当前主体获准的补救动作，精确目标由 scope、resource、binding 定位；不代替写入授权
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
public record ModelUsagePreparation(Scope scope, ResourceRef resource, DefinitionRef binding, DefinitionRef rate,
                                    State state, String reason, List<String> budgetRefs, Money approvedLimit,
                                    List<String> allowedActions) {

    public ModelUsagePreparation(Scope scope, ResourceRef resource, DefinitionRef binding, DefinitionRef rate,
                                 State state, String reason, List<String> budgetRefs, Money approvedLimit) {
        this(scope, resource, binding, rate, state, reason, budgetRefs, approvedLimit, List.of());
    }

    /**
     * 模型使用条件及兼容预算的准备阶段，区分已具备、可显式准备和当前被阻塞。
     */
    public enum State {
        // 当前模型使用条件及兼容账户已具备，但不保证余额足够或供应商调用成功。
        READY,
        // 当前资格和批准规则允许显式准备，仍需显式完成本人兼容账户初始化。
        PREPARABLE,
        // 当前存在权限、凭据、政策或预算规则等阻塞条件，需要先处理对应原因。
        BLOCKED
    }

    public ModelUsagePreparation {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(resource);
        Objects.requireNonNull(binding).requireType("binding");
        Objects.requireNonNull(rate).requireType("rate");
        Objects.requireNonNull(state);
        Objects.requireNonNull(reason);
        budgetRefs = List.copyOf(budgetRefs);
        allowedActions = List.copyOf(allowedActions);
    }
}
