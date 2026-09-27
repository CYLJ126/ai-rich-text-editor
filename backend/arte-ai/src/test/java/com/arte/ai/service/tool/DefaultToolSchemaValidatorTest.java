package com.arte.ai.service.tool;

import com.arte.ai.pojo.tool.ToolSchema;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertThrows;

public class DefaultToolSchemaValidatorTest {

    private final DefaultToolSchemaValidator validator =
            new DefaultToolSchemaValidator(new ObjectMapper());
    private final ToolSchema schema = new ToolSchema("json-schema", """
            {
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "text":{"type":"string","minLength":2,"maxLength":10},
                "count":{"type":"integer"},
                "tags":{"type":"array","maxItems":2,"items":{"type":"string"}}
              },
              "required":["text"]
            }
            """);

    @Test
    public void shouldValidateRequiredTypesAndAdditionalProperties() {
        validator.validate(schema, Map.of("text", "hello", "count", 2,
                "tags", List.of("ai")), "arguments");

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema, Map.of("count", 2), "arguments"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema, Map.of("text", "hello", "count", "two"), "arguments"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema, Map.of("text", "hello", "unknown", true), "arguments"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema, Map.of("text", "x"), "arguments"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema, Map.of("text", "hello", "tags", List.of("a", "b", "c")),
                        "arguments"));
    }
}
