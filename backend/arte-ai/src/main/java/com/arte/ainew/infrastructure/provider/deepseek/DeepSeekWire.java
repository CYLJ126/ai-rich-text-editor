package com.arte.ainew.infrastructure.provider.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * DeepSeek 交互 DTO
 * <p>
 * 供应商专有 HTTP DTO，不出现在公共接口、执行快照或结果 API 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class DeepSeekWire {

    private DeepSeekWire() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(List<Message> messages, String model, @JsonProperty("max_tokens") int maxTokens,
                          BigDecimal temperature, @JsonProperty("top_p") BigDecimal topP, List<String> stop,
                          boolean stream, @JsonProperty("stream_options") StreamOptions streamOptions,
                          Thinking thinking) {
    }

    public record Message(String role, String content) {
    }

    public record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }

    public record Thinking(String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String id, String model, List<Choice> choices, TokenUsage usage, ProviderError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Integer index, Delta delta, Delta message,
                         @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String role, String content, @JsonProperty("reasoning_content") String reasoningContent,
                        @JsonProperty("tool_calls") List<ToolProposal> toolCalls) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolProposal(Integer index, String id, String type, Function function) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenUsage(@JsonProperty("prompt_tokens") Long input, @JsonProperty("completion_tokens") Long output,
                             @JsonProperty("total_tokens") Long total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderError(String code) {
    }
}
