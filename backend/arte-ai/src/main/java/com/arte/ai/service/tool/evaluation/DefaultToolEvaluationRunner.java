package com.arte.ai.service.tool.evaluation;

import com.arte.ai.api.tool.*;
import com.arte.ai.api.tool.evaluation.ToolEvaluationRunner;
import com.arte.ai.api.tool.observability.ToolTraceRepository;
import com.arte.ai.pojo.tool.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 执行代码级评估套件，并把真实 Gateway 结果和轨迹交给评分器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
public class DefaultToolEvaluationRunner implements ToolEvaluationRunner {
    private final ToolGateway gateway;
    private final ToolRegistry registry;
    private final ToolTraceRepository traces;

    public DefaultToolEvaluationRunner(ToolGateway gateway, ToolRegistry registry,
                                       ToolTraceRepository traces) {
        this.gateway = gateway;
        this.registry = registry;
        this.traces = traces;
    }

    @Override
    public CompletionStage<List<ToolEvaluationResult>> run(ToolEvaluationSuite suite) {
        List<CompletableFuture<List<ToolEvaluationResult>>> cases = suite.cases().stream()
                .map(testCase -> evaluateCase(suite, testCase).toCompletableFuture()).toList();
        return CompletableFuture.allOf(cases.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> cases.stream().flatMap(future -> future.join().stream()).toList());
    }

    private CompletionStage<List<ToolEvaluationResult>> evaluateCase(ToolEvaluationSuite suite,
                                                                     ToolEvaluationCase testCase) {
        ToolDefinition definition = registry.resolve(testCase.call().tool()).map(Tool::getDefinition)
                .orElseThrow(() -> new IllegalArgumentException("evaluation tool is unavailable: "
                        + testCase.call().tool()));
        return gateway.invoke(testCase.call()).thenCompose(result -> {
            ToolExecutionTrace trace = traces.findByCallId(testCase.call().callId()).orElse(null);
            ToolInvocation<DynamicToolRequest> invocation = new ToolInvocation<>(testCase.call().callId(),
                    testCase.call().tool(), new DynamicToolRequest(testCase.call().arguments()),
                    testCase.call().context(), definition.defaultPolicy());
            Object actual = actualOutcome(result);
            Map<String, Object> attributes = Map.of("suiteId", suite.suiteId(),
                    "caseId", testCase.caseId());
            ToolEvaluationContext context = new ToolEvaluationContext(definition, invocation, result, trace,
                    testCase.expectedOutcome(), actual, testCase.criteria(), attributes);
            List<CompletableFuture<ToolEvaluationResult>> evaluations = suite.evaluators().stream()
                    .map(evaluator -> evaluator.evaluate(context).toCompletableFuture()).toList();
            return CompletableFuture.allOf(evaluations.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> evaluations.stream().map(CompletableFuture::join).toList());
        });
    }

    private Object actualOutcome(ToolResult<? extends ToolResponse> result) {
        if (!(result instanceof ToolResult.Succeeded<?> succeeded)) return result.status();
        return succeeded.output() instanceof DynamicToolResponse dynamic
                ? dynamic.value() : succeeded.output();
    }
}
