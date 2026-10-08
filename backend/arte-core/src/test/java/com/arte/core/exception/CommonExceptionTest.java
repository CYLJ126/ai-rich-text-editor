package com.arte.core.exception;

import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class CommonExceptionTest {

    @Test
    public void messageAndCauseConstructorKeepsExceptionResultCode() {
        BusinessException exception = new BusinessException(
                "error.ai.apiKeyDecryptFailed",
                new IllegalArgumentException("invalid cipher text")
        );

        ResultContext<Void> result = ResultContext.exception(exception);

        assertEquals(ResultCodeEnum.EXCEPTION.getCode(), result.getCode());
        assertFalse(result.getSuccess());
    }
}
