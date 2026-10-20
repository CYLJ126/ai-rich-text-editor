package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import reactor.core.publisher.Mono;

/**
 * 仅按固定原操作查询；独立于执行适配器，不提供 invoke、重发、取消或新资源访问能力。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public interface ToolOutcomeQueryAdapter {

    DefinitionRef tool();

    boolean supports(ToolExecutionSnapshot snapshot);

    String operationKey(ToolExecutionSnapshot snapshot);

    Mono<ToolOutcomeEvidence> query(ToolOutcomeCheck.Claim claim);
}
