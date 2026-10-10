package com.arte.app.web.advice;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class PlatformExceptionHandlerTest {
    private final PlatformExceptionHandler handler = new PlatformExceptionHandler();

    private MockHttpServletRequest request(String operation) {
        return new MockHttpServletRequest("POST", "/arte/workspace/" + operation);
    }

    @ParameterizedTest
    @CsvSource({"INVALID_ARGUMENT,400", "UNKNOWN_ACTION,400", "UNAUTHENTICATED,401", "ACCESS_DENIED,403",
            "POLICY_DENIED,403", "NOT_FOUND,404", "VERSION_CONFLICT,409", "MEMBER_ROLE_CONFLICT,409",
            "OWNER_TRANSFER_REQUIRED,409", "IDEMPOTENCY_CONFLICT,409", "SPACE_READ_ONLY,409", "SPACE_CLOSING,409",
            "CAPABILITY_NOT_ENABLED,409", "AUTHORITY_UNAVAILABLE,503", "IDENTITY_CONFLICT,409", "TENANT_INACTIVE,409"})
    void allPublicErrorsKeepDistinctCodeAndStatus(PlatformErrorCodeEnum code, int status) {
        var response = handler.handle(new PlatformException(code, "trace"), request("renameWorkspace"), Locale.SIMPLIFIED_CHINESE);
        assertEquals(status, response.getStatusCode().value());
        assertEquals("PLATFORM_" + code.name(), response.getBody().getCode());
        assertFalse(response.getBody().getSuccess());
        assertEquals("trace", response.getHeaders().getFirst("X-Trace-Id"));
        var details = (PlatformExceptionHandler.ErrorDetails) ((ResultContext<?>) response.getBody()).getData();
        assertEquals(code == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, details.retryableWithSameKey());
    }

    @Test
    void unexpectedFailureDoesNotExposeMessageAndPaginationKeepsItsShape() {
        var failure = new IllegalStateException("secret token and SQL parameters");
        var response = handler.handle(failure, request("createWorkspace"), Locale.SIMPLIFIED_CHINESE);
        assertEquals(500, response.getStatusCode().value());
        assertEquals("PLATFORM_INTERNAL_ERROR", response.getBody().getCode());
        assertFalse(response.getBody().getDesc().contains("secret"));
        assertNotNull(response.getHeaders().getFirst("X-Trace-Id"));
        var pageResponse = handler.handle(new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "page-trace"),
                request("listAccessibleWorkspaces"), Locale.SIMPLIFIED_CHINESE);
        assertEquals(401, pageResponse.getStatusCode().value());
        assertInstanceOf(PageView.class, pageResponse.getBody());
        assertTrue(((PageView<?>) pageResponse.getBody()).getRecords().isEmpty());
    }
}
