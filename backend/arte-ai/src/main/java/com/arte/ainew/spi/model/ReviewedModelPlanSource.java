package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.model.ReviewedModelPlan;

import java.util.List;
import java.util.Objects;

/**
 * 模型审核方案的受信读取端口
 * <p>
 * 与可执行绑定目录及主体预算配置分离。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 12:50 ✾
 */
@FunctionalInterface
public interface ReviewedModelPlanSource {

    /**
     * 在调用方已有的管理事务内读取一致审核修订，不授予平台配置管理权。
     */
    Snapshot read();

    /**
     * @param plans           不可变审核方案，不含可执行模型绑定
     * @param connections     对应审核连接，服务端内部使用
     * @param rates           对应精确费率
     * @param catalogRevision 一致目录修订
     * @param releaseRef      内部发布证据，不代替资源授权
     */
    record Snapshot(List<ReviewedModelPlan> plans, List<ConnectionDefinition> connections,
                    List<RateDefinition> rates, long catalogRevision, String releaseRef) {

        public Snapshot {
            plans = List.copyOf(plans);
            connections = List.copyOf(connections);
            rates = List.copyOf(rates);
            if (catalogRevision < 1) {
                throw new IllegalArgumentException("Invalid reviewed revision");
            }
            Objects.requireNonNull(releaseRef);
        }
    }
}
