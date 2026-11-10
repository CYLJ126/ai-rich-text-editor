package com.arte.ainew.application.budget;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.spi.auth.BudgetReconciliationAccess;
import com.arte.ainew.spi.persistence.ReconciliationStore;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.ExecutionOwner;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

/**
 * 人工对账服务
 * <p>
 * 当前本人或获明确账户授权的责任人凭实际账单核对；目标 owner 与真实 actor 分离。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public final class ManualReconciliationService {

    /**
     * 核对分页及当前提交提示。
     *
     * @param page         当前有权读取的财务记录
     * @param canReconcile 当前提示，提交仍须在事务中重新授权
     */
    public record PendingPage(ConversationPage<Reconciliation.Pending> page, boolean canReconcile) {
    }

    private final AdmissionAuthorization authorization;
    private final BudgetAccountQueryService accounts;
    private final ReconciliationStore store;
    private BudgetReconciliationAccess access;

    /**
     * 创建人工核对服务，账户授权适配器由组合模块另行安装。
     *
     * @param authorization 当前操作的读写授权服务
     * @param accounts      本人预算账户及核对责任查询服务
     * @param store         核对记录、结算和幂等回执存储
     */
    public ManualReconciliationService(AdmissionAuthorization authorization, BudgetAccountQueryService accounts, ReconciliationStore store) {
        this.authorization = authorization;
        this.accounts = accounts;
        this.store = store;
    }

    /**
     * 由组合模块安装公共账户授权；未安装时管理路径默认拒绝。
     *
     * @param access 具体预算账户的授权适配器
     */
    public void accountAccess(BudgetReconciliationAccess access) {
        this.access = access;
    }

    /**
     * 分页读取本人预算账户的待核对记录，并根据当前责任状态提示是否可自行核对。
     *
     * @param budgetRef 预算引用
     * @param current   当前页码
     * @param size      每页记录数
     * @param context   当前读取者的执行上下文
     * @return 待核对记录及当前自行核对提示，提交时仍须重新授权
     */
    public Mono<PendingPage> pending(String budgetRef, long current, long size, ExecutionContext context) {
        return accounts.account(budgetRef, context).flatMap(account ->
                store.pendingReconciliations(account.owner(), budgetRef, current, size).flatMap(page ->
                        accounts.handling(account, context).map(handling -> new PendingPage(page, handling.status().equals("SELF_SERVICE")))));
    }

    /**
     * 按具体账户授权分页读取待核对财务记录，移除会话引用，并查询当前核对操作配置。
     *
     * @param accountRef 目标预算账户的公共资源引用
     * @param current    当前页码
     * @param size       每页记录数
     * @param context    真实读取者的执行上下文
     * @return 隐去会话引用的财务记录及核对操作提示，提示不代替提交授权
     */
    public Mono<PendingPage> managedPending(ResourceRef accountRef, long current, long size, ExecutionContext context) {
        return managed(accountRef, context, false).flatMap(permit -> store.pendingReconciliations(permit, current, size)
                .flatMap(page -> authorization.isOperationConfigured(context, AdmissionAuthorization.BUDGET_ADMIN)
                        .map(allowed -> new PendingPage(new ConversationPage<>(page.current(), page.size(), page.total(),
                                page.records().stream().map(p -> new Reconciliation.Pending(p.invocationId(), null,
                                        p.state(), p.invocationVersion(), p.reservationId(), p.reservationVersion(), p.budgetRef(),
                                        p.reserved(), p.remoteRequestId(), p.acceptedAt(), p.pendingSince())).toList()), allowed))));
    }

    /**
     * 核验写权限和账户币种后，凭实际费用及证据提交本人账户核对。
     * 已安装账户授权适配器时，提交还须携带具体账户许可供存储事务重检。
     *
     * @param budgetRef          预算引用
     * @param invocationId       待核对调用 ID
     * @param invocationVersion  调用的预期版本
     * @param reservationId      待核对预留 ID
     * @param reservationVersion 预留的预期版本
     * @param charge             有证据支持的实际费用
     * @param evidenceRef        费用证据引用
     * @param note               人工核对说明
     * @param executionEnded     是否已确认远端执行结束
     * @param context            当前核对者的执行上下文，含幂等键及审计身份
     * @return 与目标预算一致的核对回执
     */
    public Mono<Reconciliation.Receipt> confirm(String budgetRef, String invocationId, long invocationVersion,
                                                String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                                boolean executionEnded, ExecutionContext context) {
        return authorization.requireWrite(context, AdmissionAuthorization.BUDGET_ADMIN).flatMap(current ->
                accounts.account(budgetRef, current).flatMap(account -> {
                    if (!account.limit().currency().equals(charge.currency())) {
                        throw new AdmissionException(ResultCodeEnum.AI_CURRENCY_MISMATCH);
                    }
                    Reconciliation.Confirm command = command(account.owner(), budgetRef, invocationId, invocationVersion,
                            reservationId, reservationVersion, charge, evidenceRef, note, executionEnded, current);
                    if (access == null) {
                        return result(store.confirmReconciliation(command), budgetRef);
                    }
                    return access.authorize(BudgetAccountQueryService.accountRef(account), current, true)
                            .flatMap(permit -> result(store.confirmReconciliation(command, permit), budgetRef));
                }));
    }

    /**
     * 依据具体账户的提交许可代办核对，校验目标预算及币种，并保留真实操作者身份。
     *
     * @param accountRef         目标预算账户的公共资源引用
     * @param budgetRef          必须与授权账户一致的预算引用
     * @param invocationId       待核对调用 ID
     * @param invocationVersion  调用的预期版本
     * @param reservationId      待核对预留 ID
     * @param reservationVersion 预留的预期版本
     * @param charge             有证据支持的实际费用
     * @param evidenceRef        费用证据引用
     * @param note               人工核对说明
     * @param executionEnded     是否已确认远端执行结束
     * @param context            真实核对者的执行上下文，不能替换为账户所有者
     * @return 与目标预算一致的核对回执
     */
    public Mono<Reconciliation.Receipt> confirmManaged(ResourceRef accountRef, String budgetRef, String invocationId, long invocationVersion,
                                                       String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                                       boolean executionEnded, ExecutionContext context) {
        return managed(accountRef, context, true).flatMap(permit -> {
            BudgetCommands.Account account = permit.account();
            if (!account.budgetRef().equals(budgetRef)) {
                throw new AccessDeniedException("Account target mismatch");
            }
            if (!account.limit().currency().equals(charge.currency())) {
                throw new AdmissionException(ResultCodeEnum.AI_CURRENCY_MISMATCH);
            }
            return result(store.confirmReconciliation(command(account.owner(), budgetRef, invocationId, invocationVersion, reservationId,
                    reservationVersion, charge, evidenceRef, note, executionEnded, context), permit), budgetRef);
        });
    }

    /**
     * 构造核对命令，将目标账户所有者与真实操作者、追踪信息和幂等键分别保存。
     *
     * @param owner              目标账本所属执行主体
     * @param budgetRef          目标预算引用
     * @param invocationId       待核对调用 ID
     * @param invocationVersion  调用的预期版本
     * @param reservationId      待核对预留 ID
     * @param reservationVersion 预留的预期版本
     * @param charge             实际费用
     * @param evidenceRef        费用证据引用
     * @param note               人工核对说明
     * @param executionEnded     是否已确认远端执行结束
     * @param actor              真实操作者的执行上下文
     * @return 供存储事务校验和提交的核对命令
     */
    private Reconciliation.Confirm command(ExecutionOwner owner, String budgetRef, String invocationId, long invocationVersion,
                                           String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                           boolean executionEnded, ExecutionContext actor) {
        return new Reconciliation.Confirm(owner, actor.authorization().principal(), actor.traceId(), actor.idempotencyKey(), budgetRef,
                invocationId, invocationVersion, reservationId, reservationVersion, charge, evidenceRef, note, executionEnded);
    }

    /**
     * 将存储拒绝转换为应用异常，并校验成功回执确实属于目标预算。
     *
     * @param work      核对存储操作
     * @param budgetRef 预期的预算引用
     * @return 通过存储结果及预算归属校验的回执
     */
    private Mono<Reconciliation.Receipt> result(Mono<StoreOutcome<Reconciliation.Receipt>> work, String budgetRef) {
        return work.map(result -> {
            if (!result.successful()) {
                throw AdmissionException.fromStoreRejection(result.code());
            }
            if (!result.value().budgetRef().equals(budgetRef)) {
                throw new IllegalStateException("Reconciliation account mismatch");
            }
            return result.value();
        });
    }

    /**
     * 先校验操作级读写权限，再取得目标账户许可；未安装账户授权适配器时拒绝管理路径。
     *
     * @param accountRef 目标账户的公共资源引用
     * @param actor      真实操作者的执行上下文
     * @param write      是否申请核对提交许可，否则仅申请读取许可
     * @return 供后续存储事务重新校验的账户许可
     */
    private Mono<BudgetReconciliationAccess.Permit> managed(ResourceRef accountRef, ExecutionContext actor, boolean write) {
        if (access == null) {
            return Mono.error(new AccessDeniedException("Account authorization unavailable"));
        }
        return (write ? authorization.requireWrite(actor, AdmissionAuthorization.BUDGET_ADMIN) : authorization.require(actor, AdmissionAuthorization.READ))
                .flatMap(current -> access.authorize(accountRef, current, write));
    }

    /**
     * 校验本人账户访问资格后，按调用和原幂等键读取回执，并核验预算归属。
     *
     * @param budgetRef    目标预算引用
     * @param invocationId 原核对调用 ID
     * @param key          原核对操作的幂等键
     * @param context      当前读取者的执行上下文
     * @return 核对回执；不存在或不属于目标预算时返回未找到错误
     */
    public Mono<Reconciliation.Receipt> receipt(String budgetRef, String invocationId, String key, ExecutionContext context) {
        return accounts.account(budgetRef, context).then(store.reconciliationReceipt(ExecutionOwners.from(context), invocationId, key))
                .filter(receipt -> receipt.budgetRef().equals(budgetRef))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }

    /**
     * 凭目标账户读取许可查询原核对回执，保持真实读取者与目标账户所有者分离。
     *
     * @param accountRef   目标账户的公共资源引用
     * @param invocationId 原核对调用 ID
     * @param key          原核对操作的幂等键
     * @param context      真实读取者的执行上下文
     * @return 当前有权读取的核对回执；不存在时返回未找到错误
     */
    public Mono<Reconciliation.Receipt> managedReceipt(ResourceRef accountRef, String invocationId, String key, ExecutionContext context) {
        return managed(accountRef, context, false).flatMap(permit -> store.reconciliationReceipt(permit, invocationId, key))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }
}
