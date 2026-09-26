package com.arte.ai.api.tool.evaluation;

import com.arte.ai.pojo.tool.ToolEvaluationContext;
import com.arte.ai.pojo.tool.ToolEvaluationResult;

import java.util.concurrent.CompletionStage;

/**
 * 独立于被评工具的评估器。
 * <p>
 * 可实现规则评分、Schema 评分、LLM-as-Judge、轨迹评分或环境结果评分。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolEvaluator {

    String getName();

    CompletionStage<ToolEvaluationResult> evaluate(ToolEvaluationContext context);
}
