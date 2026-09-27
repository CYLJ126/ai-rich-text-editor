package com.arte.ai.service.tool.security;

import com.arte.ai.config.ToolExecutionProperties;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.Assert.assertThrows;

public class ToolInputLimitValidatorTest {

    @Test
    public void shouldRejectRawCredentialsAndOversizedArguments() {
        ToolExecutionProperties properties = new ToolExecutionProperties();
        properties.setMaximumInputBytes(16);
        ToolInputLimitValidator validator = new ToolInputLimitValidator(new ObjectMapper(), properties);

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("nested", Map.of("apiKey", "plain-secret"))));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("text", "this input is too long")));
    }
}
