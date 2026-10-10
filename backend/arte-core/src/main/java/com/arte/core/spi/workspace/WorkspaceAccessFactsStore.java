package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceAccessFacts;

import java.util.Set;

/**
 * 展示型授权的有界批量事实读取，公共应用层仍负责身份、成员、政策、生命周期及动作判定。
 * 不是事务内最终授权入口，不能替代治理门控下的 lock／findCurrent。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 12:35 ✾
 */
public interface WorkspaceAccessFactsStore {

    /**
     * 按稳定主体读取原始事实；租户及完整空间范围分别最多 100 项，空间所属租户必须包含在 tenantIds 中。
     * 使用主库及调用方读上下文，不另开事务、不返回跨请求缓存或只读副本；成员按 subjectId 与完整范围过滤。
     * 缺失记录以映射缺项表示，故障须抛出异常，不能伪装为不存在。映射键必须与记录范围一致。
     * includeDefaultPreferences 为 true 时，批量读取所请求个人租户的 personalSubjectId 对应偏好，按租户 ID 返回；否则该映射为空。
     * 适配器须使用绑定参数、集合查询／连接查询，使数据库往返数受固定表数约束；禁止循环委托逐项 find 的默认回退。
     * 不要求所有表为同一时点快照；返回事实仅在本批展示判断中复用，下一批重新读取，写入必须重新做事务内最终授权。
     */
    WorkspaceAccessFacts read(String subjectId, Set<String> tenantIds, Set<Scope> scopes, boolean includeDefaultPreferences);
}
