package com.arte.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

public class SafeExceptionDiagnosticsTest {

    @Test
    public void keepsFramesCausesAndSuppressedTypesWithoutOriginalMessagesOrMutation() {
        var sql = new SQLException("private-sql-password", "08006", 12);
        var failure = new IllegalStateException("private-model-text", sql);
        failure.addSuppressed(new IllegalArgumentException("private-tool-arguments"));
        var safe = SafeExceptionDiagnostics.forLogging(failure);
        var text = new StringWriter();
        safe.printStackTrace(new PrintWriter(text));
        assertTrue(text.toString().contains("java.sql.SQLException"));
        assertTrue(text.toString().contains("Caused by:"));
        assertTrue(text.toString().contains("Suppressed:"));
        assertTrue(text.toString().contains("SafeExceptionDiagnosticsTest.java"));
        assertFalse(text.toString().contains("private-"));
        assertArrayEquals(failure.getStackTrace(), safe.getStackTrace());
        assertSame(sql, failure.getCause());
        assertEquals("private-model-text", failure.getMessage());
        assertEquals("private-sql-password", sql.getMessage());
    }

    @Test
    public void cyclesAndOversizedGraphsStayBounded() {
        var first = new Exception("private-first");
        var second = new Exception("private-second");
        first.initCause(second);
        second.initCause(first);
        assertNull(SafeExceptionDiagnostics.forLogging(first).getCause().getCause().getCause());
        Throwable nested = new Exception("private-bottom");
        for (int i = 0; i < 200; i++) nested = new Exception("private-level", nested);
        int depth = 0;
        for (Throwable value = SafeExceptionDiagnostics.forLogging(nested); value != null; value = value.getCause())
            depth++;
        assertTrue(depth <= 33);
        assertNull(SafeExceptionDiagnostics.forLogging(null));
    }
}
