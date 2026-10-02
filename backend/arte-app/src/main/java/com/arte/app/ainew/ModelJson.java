package com.arte.app.ainew;

import com.google.gson.*;
import com.arte.ai.model.generation.ModelResult;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.model.budget.Usage;
import com.arte.base.model.execution.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 显式类型字段编码；不反序列化任意 Java 对象、不保存 SDK 对象或凭据。
 */
final class ModelJson {
    private ModelJson() {
    }

    static String result(ModelResult result) {
        if (result == null) return null;
        var object = new JsonObject();
        var output = new JsonArray();
        for (var part : result.output()) {
            if (!(part instanceof TextPart text)) throw new IllegalArgumentException("only text output is supported");
            output.add(text.text());
        }
        if (!result.toolCalls().isEmpty() || result.structuredOutput() != null)
            throw new IllegalArgumentException("unsupported model result");
        object.add("text", output);
        var usage = result.usage();
        object.addProperty("input", usage.inputTokens());
        object.addProperty("output", usage.outputTokens());
        object.addProperty("cost", usage.reportedCost());
        object.addProperty("currency", usage.currency());
        return object.toString();
    }

    static ModelResult result(String encoded) {
        if (encoded == null) return null;
        var object = JsonParser.parseString(encoded).getAsJsonObject();
        var output = new java.util.ArrayList<com.arte.ai.model.message.ContentPart>();
        object.getAsJsonArray("text").forEach(value -> output.add(new TextPart(value.getAsString())));
        return new ModelResult(output, List.of(), null, new Usage(number(object, "input"), number(object, "output"),
                value(object, "cost") == null ? null : new BigDecimal(value(object, "cost")), value(object, "currency")));
    }

    static String error(ExecutionError error) {
        if (error == null) return null;
        var object = new JsonObject();
        object.addProperty("code", error.code());
        object.addProperty("stage", error.failureStage());
        object.addProperty("retry", error.retryable());
        object.addProperty("effects", error.sideEffectStatus().name());
        object.addProperty("certainty", error.resultCertainty().name());
        object.addProperty("trace", error.correlationId());
        return object.toString();
    }

    static ExecutionError error(String encoded) {
        if (encoded == null) return null;
        var o = JsonParser.parseString(encoded).getAsJsonObject();
        return new ExecutionError(value(o, "code"), value(o, "stage"), o.get("retry").getAsBoolean(), SideEffectStatus.valueOf(value(o, "effects")),
                ResultCertainty.valueOf(value(o, "certainty")), value(o, "trace"));
    }

    static String value(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static Long number(JsonObject object, String key) {
        String value = value(object, key);
        return value == null ? null : new BigDecimal(value).longValueExact();
    }
}
