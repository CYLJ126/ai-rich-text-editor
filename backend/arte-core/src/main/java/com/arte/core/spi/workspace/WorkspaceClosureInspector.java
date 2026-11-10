package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.ClosureBlocker;

import java.util.List;
import java.util.Map;

/**
 * 保留型软删除的领域检查端口：只读权威状态，不取消任务、不清理正文、不修改账本。
 * 不能确认时必须返回阻塞项；完整扫描和资金收敛前不能报告就绪。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
public interface WorkspaceClosureInspector {

    /**
     * 稳定且唯一的领域标识。
     */
    String domain();

    /**
     * 在公共治理门控内检查固定空间；不得进行网络请求或新建执行。
     */
    Inspection inspect(Scope scope);

    /**
     * 仅含计数和原因码，不含正文、密钥、账单金额或其他主体身份。
     */
    record Inspection(List<ClosureBlocker> blockers, Map<String, Long> retainedResources) {
        public Inspection {
            blockers = List.copyOf(blockers);
            retainedResources = Map.copyOf(retainedResources);
        }
    }
}
