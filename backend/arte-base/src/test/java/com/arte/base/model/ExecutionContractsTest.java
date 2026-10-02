package com.arte.base.model;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.error.ErrorCode;
import com.arte.base.model.execution.*;
import com.arte.base.model.resource.ResourceRef;
import org.junit.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.Assert.*;

public class ExecutionContractsTest {

    @Test
    public void unknownOutcomeOrPossibleSideEffectRequiresReconciliationEvenWhenRetryable() {
        ExecutionError pureFailure = error(true, SideEffectStatus.NONE, ResultCertainty.CONFIRMED);
        assertTrue(pureFailure.canRetryWithoutReconciliation());
        assertFalse(error(true, SideEffectStatus.NONE, ResultCertainty.UNKNOWN).canRetryWithoutReconciliation());
        assertFalse(error(true, SideEffectStatus.UNKNOWN, ResultCertainty.CONFIRMED).canRetryWithoutReconciliation());
        assertFalse(error(true, SideEffectStatus.OCCURRED, ResultCertainty.CONFIRMED).canRetryWithoutReconciliation());
        assertFalse(error(false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED).canRetryWithoutReconciliation());
    }

    @Test
    public void domainErrorsExtendCommonContractWithoutInternationalizationOrLegacyEnums() {
        ErrorCode domainCode = () -> "article.write_denied";
        ExecutionError error = ExecutionError.of(domainCode, "apply", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, "trace-1");
        assertEquals("article.write_denied", error.code());
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionError.of(() -> "", "apply", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }

    @Test
    public void diagnosticCauseIsKeptSeparateFromPublicError() {
        ExecutionError error = error(false, SideEffectStatus.UNKNOWN, ResultCertainty.UNKNOWN);
        Throwable diagnostic = new IllegalStateException("private-diagnostic-detail");
        BaseException exception = new BaseException(error, diagnostic);
        assertSame(error, exception.error());
        assertSame(diagnostic, exception.getCause());
        assertEquals(error.code(), exception.getMessage());
        assertFalse(exception.error().toString().contains("private-diagnostic-detail"));
    }

    @Test
    public void acceptedExecutionSupportsLocalAndHttpsQueryLocations() {
        AcceptedExecution local = accepted(URI.create("/ai/executions/execution-1"));
        AcceptedExecution remote = accepted(URI.create("https://example.test/ai/executions/execution-1"));
        assertFalse(local.statusUri().isAbsolute());
        assertEquals("example.test", remote.statusUri().getHost());
    }

    @Test
    public void queryLocationsDoNotEmbedCredentialsOrAcceptUnsafeAddressForms() {
        for (String location : new String[]{
                "https://user:password@example.test/executions/1",
                "/executions/1?token=secret",
                "/executions/1#secret",
                "//example.test/executions/1",
                "executions/1",
                "file:///tmp/output",
                "javascript:alert(1)"}) {
            assertThrows(location, IllegalArgumentException.class, () -> accepted(URI.create(location)));
        }
    }

    @Test
    public void replayEventHasOneUnambiguousPayloadRepresentation() {
        Instant recordedAt = Instant.parse("2026-10-02T08:00:00Z");
        ResourceRef stored = ResourceRef.saved("execution-output", "output-1", "batch-1");
        ExecutionEvent<String> inline = new ExecutionEvent<>("execution-1", null, 0, "output.batch", recordedAt, "text", null);
        ExecutionEvent<String> reference = new ExecutionEvent<>("execution-1", "attempt-1", 1, "output.batch", recordedAt, null, stored);
        assertEquals("text", inline.payload());
        assertEquals(stored, reference.payloadRef());
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent<>("execution-1", null, 1, "output.batch", recordedAt, "text", stored));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent<>("execution-1", null, 1, "output.batch", recordedAt, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent<>("execution-1", null, -1, "output.batch", recordedAt, "text", null));
    }

    private static ExecutionError error(boolean retryable, SideEffectStatus effects, ResultCertainty certainty) {
        return ExecutionError.of(CommonErrorCode.INTERNAL_ERROR, "dispatch", retryable, effects, certainty, null);
    }

    private static AcceptedExecution accepted(URI statusUri) {
        return new AcceptedExecution("execution-1", "invocation", "accepted", statusUri,
                URI.create("/ai/executions/execution-1/events"));
    }
}
