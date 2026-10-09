package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;

import java.util.List;
import java.util.Objects;

/**
 * 关闭预检快照；正式提交仍需当前版本与领域依赖重检。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosurePreview(
        Scope scope,
        List<ClosureBlocker> blockers) {
    public ClosurePreview {
        Objects.requireNonNull(scope, "scope");
        blockers = List.copyOf(blockers);
    }
}
