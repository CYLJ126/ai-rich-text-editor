package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.*;

import java.util.List;

/**
 * 工作空间关闭流程服务
 * <p>
 * 公共模块不读取业务表。预检、准备与处理固定目标必须幂等，无证据返回 WAITING_EVIDENCE，不能默认 DONE。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceClosureParticipant {

    /**
     * 返回关闭参与者的稳定领域标识。
     */
    String domain();

    /**
     * 预检指定空间的领域关闭阻塞项，不执行资源清理。
     */
    List<ClosureBlocker> preview(Scope scope);

    /**
     * 为关闭操作幂等准备固定目标的领域工作项。
     */
    List<ClosureWorkItem> prepare(ClosureOperation operation);

    /**
     * 使用已校验的收尾授权幂等处理工作项，返回真实处理进度。
     */
    ClosureProgress process(ClosureWorkItem item, VerifiedCleanupAuthorization authorization);
}
