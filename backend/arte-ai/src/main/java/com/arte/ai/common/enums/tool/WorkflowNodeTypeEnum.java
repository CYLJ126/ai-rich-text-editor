package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工作流程节点类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum WorkflowNodeTypeEnum implements IEnum<String>, MyEnum<String> {
    START("start", "开始节点"),
    END("end", "结束节点"),
    TOOL("tool", "工具节点"),
    LLM("llm", "大模型节点"),
    ROUTER("router", "路由节点"),
    PARALLEL("parallel", "并行节点"),
    JOIN("join", "汇聚节点"),
    FOREACH("foreach", "循环节点"),
    EVALUATOR("evaluator", "评估节点"),
    HUMAN_APPROVAL("human-approval", "人工审批节点"),
    SUB_WORKFLOW("sub-workflow", "子工作流程节点"),
    AGENT("agent", "智能体节点"),
    ;

    private final String value;
    private final String description;

    WorkflowNodeTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
