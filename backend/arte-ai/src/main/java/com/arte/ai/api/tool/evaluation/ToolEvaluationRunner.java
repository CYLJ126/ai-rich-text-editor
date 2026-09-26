package com.arte.ai.api.tool.evaluation;

import com.arte.ai.pojo.tool.ToolEvaluationResult;
import com.arte.ai.pojo.tool.ToolEvaluationSuite;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 离线或回归评估的统一运行入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolEvaluationRunner {

    CompletionStage<List<ToolEvaluationResult>> run(ToolEvaluationSuite suite);
}
