package com.arte.ainew.spi.assistant;

import com.arte.ainew.pojo.assistant.Assistants.Trial;
import com.arte.core.pojo.platform.Scope;

/**
 * Verifies the original, successful invocation and its snapshot in the caller's current transaction.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:09 ✾
 */
@FunctionalInterface
public interface AssistantTrialVerifier {

    void requireSucceeded(Scope scope, Trial trial);
}
