package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;

import java.util.List;
import java.util.Objects;

/**
 * 关闭预检快照；正式提交仍需当前版本与领域依赖重检。
 *
 * @param scope    目标空间范围，包含租户 ID 和空间 ID
 * @param blockers 预检时发现的关闭阻塞项
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosurePreview(Scope scope, List<ClosureBlocker> blockers) {

    public ClosurePreview {
        Objects.requireNonNull(scope, "scope");
        blockers = List.copyOf(blockers);
    }
}
