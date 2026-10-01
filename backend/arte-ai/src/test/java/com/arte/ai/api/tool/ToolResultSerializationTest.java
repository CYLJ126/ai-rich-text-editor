package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.CategoryEnum;
import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.pojo.tool.DynamicToolResponse;
import com.arte.ai.pojo.tool.ToolError;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolTaskHandle;
import com.arte.core.pojo.ResultContext;
import com.arte.core.serialize.SerializerFactory;
import org.junit.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ToolResultSerializationTest {

    private final List<ObjectMapper> mappers = List.of(
            SerializerFactory.buildJsonMapperWithTypeProperty(),
            SerializerFactory.buildJsonMapperWithoutTypeProperty());

    @Test
    public void shouldIncludeSucceededStatusAndOutputInApiResponse() {
        ToolResult<DynamicToolResponse> result = new ToolResult.Succeeded<>(
                new DynamicToolResponse("竺春", "\"竺春\""),
                List.of(), List.of(), null, Map.of("springAiTool", "random-name"));

        for (ObjectMapper mapper : mappers) {
            JsonNode data = serializeData(mapper, result);
            assertEquals("succeeded", data.path("status").asString());
            assertEquals("竺春", data.path("output").path("value").asString());
            assertEquals("\"竺春\"", data.path("output").path("rawContent").asString());
        }
    }

    @Test
    public void shouldIncludeAcceptedStatusAndTaskHandleInApiResponse() {
        Instant now = Instant.parse("2026-10-01T06:00:00Z");
        ToolTaskHandle handle = new ToolTaskHandle("task-1", "call-1",
                new ToolReference("local", "random-name", "1.0.0"),
                ToolTaskHandle.Status.QUEUED, null, null, null, now, now, 0, Map.of());
        ToolResult<DynamicToolResponse> result = new ToolResult.Accepted<>(handle, Map.of());

        for (ObjectMapper mapper : mappers) {
            JsonNode data = serializeData(mapper, result);
            assertEquals("accepted", data.path("status").asString());
            assertEquals("task-1", data.path("taskHandle").path("taskId").asString());
            assertEquals("QUEUED", data.path("taskHandle").path("status").asString());
        }
    }

    @Test
    public void shouldPreserveSuspendedStatusesInApiResponse() {
        for (ToolResultStatusEnum status : List.of(
                ToolResultStatusEnum.REQUIRES_APPROVAL, ToolResultStatusEnum.PAUSED)) {
            ToolResult<DynamicToolResponse> result = new ToolResult.Suspended<>(
                    status, "approval-1", "resume-token", Map.of());
            for (ObjectMapper mapper : mappers) {
                JsonNode data = serializeData(mapper, result);
                assertEquals(status.getValue(), data.path("status").asString());
                assertEquals("resume-token", data.path("resumeToken").asString());
            }
        }
    }

    @Test
    public void shouldPreserveUnsuccessfulStatusesInApiResponse() {
        ToolError error = new ToolError("TOOL_ERROR", CategoryEnum.INTERNAL,
                "Execution failed", false, Map.of());
        for (ToolResultStatusEnum status : List.of(ToolResultStatusEnum.FAILED,
                ToolResultStatusEnum.DENIED, ToolResultStatusEnum.CANCELLED,
                ToolResultStatusEnum.TIMED_OUT)) {
            ToolResult<DynamicToolResponse> result = new ToolResult.Unsuccessful<>(
                    status, error, null, Map.of());
            for (ObjectMapper mapper : mappers) {
                JsonNode data = serializeData(mapper, result);
                assertEquals(status.getValue(), data.path("status").asString());
                assertEquals("TOOL_ERROR", data.path("error").path("code").asString());
            }
        }
    }

    private JsonNode serializeData(ObjectMapper mapper, ToolResult<?> result) {
        ResultContext<ToolResult<?>> response = new ResultContext<>();
        response.setSuccess(true);
        response.setData(result);
        return mapper.readTree(mapper.writeValueAsString(response)).path("data");
    }
}
