package com.arte.ainew.pojo.control;

import com.arte.ainew.pojo.budget.Money;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.Objects;

/**
 * 固定版本费率的业务读取对象；不改变现有配置及持久化快照类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public record RateDefinition(DefinitionRef definition, Money inputPerMillion, Money outputPerMillion,
                             Money toolPerCall) {

    public RateDefinition {
        Objects.requireNonNull(definition).requireType("rate");
        if (toolPerCall == null) {
            Objects.requireNonNull(inputPerMillion);
            Objects.requireNonNull(outputPerMillion);
            if (!inputPerMillion.currency().equals(outputPerMillion.currency()))
                throw new IllegalArgumentException("Rate currency mismatch");
        } else if (inputPerMillion != null || outputPerMillion != null)
            throw new IllegalArgumentException("Tool rate must not contain Token prices");
    }
}
