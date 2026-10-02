package com.arte.app.ainew;

import com.arte.ai.model.generation.*;
import com.arte.ai.model.message.*;
import com.arte.ai.model.budget.*;
import com.arte.ai.spi.adapter.*;
import com.google.gson.*;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 最小兼容聊天协议适配：文本、一个结果、不流式；工具／模态／结构化输出明确拒绝。
 */
public final class CompatibleChatProviderAdapter implements ProviderAdapter {
    private final ConnectionRuntime runtime;
    private final String model;
    private final BigDecimal inputPrice, outputPrice;
    private final BudgetQuote quote;
    private final int maxInputBytes, maxOutputTokens;

    public CompatibleChatProviderAdapter(ConnectionRuntime runtime, String model, BigDecimal inputPrice, BigDecimal outputPrice,
                                         BudgetQuote quote, int maxInputBytes, int maxOutputTokens) {
        this.runtime = Objects.requireNonNull(runtime);
        this.model = com.arte.base.validation.ContractChecks.identifier(model, "model");
        this.inputPrice = Objects.requireNonNull(inputPrice);
        this.outputPrice = Objects.requireNonNull(outputPrice);
        this.quote = Objects.requireNonNull(quote);
        if (inputPrice.signum() < 0 || outputPrice.signum() < 0 || inputPrice.scale() > 8 || outputPrice.scale() > 8
                || maxInputBytes <= 0 || maxInputBytes > 65536 || maxOutputTokens <= 0 || maxOutputTokens > 32768)
            throw new IllegalArgumentException("invalid controlled model limits or pricing");
        this.maxInputBytes = maxInputBytes;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public boolean supports(ModelPlan plan) {
        return "chat-completions".equals(plan.connection().protocol());
    }

    @Override
    public PreparedModelCall prepare(ModelPlan plan, GenerationRequest request) {
        if (!request.tools().isEmpty() || request.outputSchema() != null || request.messages().size() > 128)
            throw new IllegalArgumentException("unsupported model input");
        var messages = new JsonArray();
        boolean hasUser = false;
        for (var message : request.messages()) {
            if (message.role() == MessageRole.TOOL) throw new IllegalArgumentException("tool messages unsupported");
            hasUser |= message.role() == MessageRole.USER;
            var text = new StringBuilder();
            for (var part : message.parts()) {
                if (!(part instanceof TextPart value))
                    throw new IllegalArgumentException("only text messages are supported");
                if (text.length() + value.text().length() > maxInputBytes)
                    throw new IllegalArgumentException("model input too large");
                text.append(value.text());
            }
            var item = new JsonObject();
            item.addProperty("role", message.role().name().toLowerCase(Locale.ROOT));
            item.addProperty("content", text.toString());
            messages.add(item);
        }
        if (!hasUser) throw new IllegalArgumentException("a user message is required");
        int maximum = request.options().maxOutputTokens() == null ? maxOutputTokens : request.options().maxOutputTokens();
        if (maximum > maxOutputTokens) throw new IllegalArgumentException("output token limit exceeds binding limit");
        var body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", maximum);
        if (request.options().temperature() != null) body.addProperty("temperature", request.options().temperature());
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxInputBytes) throw new IllegalArgumentException("model input too large");
        // 文本字节+每条消息固定余量作为保守预留估算；供应商分词仍需正式计费验证。
        var upper = inputPrice.multiply(BigDecimal.valueOf(bytes.length + request.messages().size() * 64L))
                .add(outputPrice.multiply(BigDecimal.valueOf(maximum)));
        if (upper.compareTo(quote.maximumAmount()) > 0)
            throw new IllegalArgumentException("request exceeds per-call budget quote");
        return new PreparedModelCall(plan, ModelKeys.digest(bytes), bytes.length, checkpoint -> parse(runtime.exchange(plan.connection(), bytes.clone(), checkpoint)));
    }

    ModelResult parse(byte[] response) {
        var object = JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
        var choices = object.getAsJsonArray("choices");
        if (choices == null || choices.size() != 1) throw new IllegalArgumentException("invalid model choices");
        var choice = choices.get(0).getAsJsonObject();
        String reason = ModelJson.value(choice, "finish_reason");
        if (!"stop".equals(reason) && !"length".equals(reason))
            throw new IllegalArgumentException("unsupported finish reason");
        var message = choice.getAsJsonObject("message");
        if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull() && message.getAsJsonArray("tool_calls").size() > 0
                || message.has("function_call") && !message.get("function_call").isJsonNull())
            throw new IllegalArgumentException("tool output unsupported");
        String content = ModelJson.value(message, "content");
        if (content == null) throw new IllegalArgumentException("missing model content");
        if (!message.get("content").isJsonPrimitive() || !message.get("content").getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("invalid model content");
        Usage usage = Usage.unknown();
        var reported = object.get("usage");
        if (reported != null && !reported.isJsonNull()) {
            var values = reported.getAsJsonObject();
            Long input = ModelJson.number(values, "prompt_tokens"), output = ModelJson.number(values, "completion_tokens");
            BigDecimal cost = input == null || output == null ? null : inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output))).setScale(8, RoundingMode.CEILING);
            usage = new Usage(input, output, cost, cost == null ? null : quote.currency());
        }
        return new ModelResult(List.of(new TextPart(content)), List.of(), null, usage);
    }
}
