package com.arte.ainew.web.response;

import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.spi.persistence.ExecutionEventStore;

import java.util.List;

/**
 * 耐久事件单页；空页只表示本次没有新增事件，不能证明调用完成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:24 ✾
 */
public record InvocationEventsResponse(String invocationId, List<ExecutionEvent<?>> events,
                                       ExecutionEvent.Cursor nextCursor, long retainedAfterSequence) {
    public InvocationEventsResponse {
        events = List.copyOf(events);
    }

    public static InvocationEventsResponse from(String invocationId, ExecutionEventStore.Page page) {
        return new InvocationEventsResponse(invocationId, page.events(), page.next(), page.retainedAfterSequence());
    }
}
