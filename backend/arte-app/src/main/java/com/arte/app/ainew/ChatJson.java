package com.arte.app.ainew;

import com.arte.ai.model.context.ContextHistoryRef;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.ContentPart;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.base.model.resource.ResourceRef;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 显式持久化格式：不启用 Java 类型标签或 SDK 对象反序列化。
 */
final class ChatJson {
    private ChatJson() {
    }

    static String messages(List<Message> messages) {
        var array = new JsonArray();
        for (var message : messages) {
            var object = new JsonObject();
            object.addProperty("role", message.role().name());
            var parts = new JsonArray();
            for (var part : message.parts()) {
                if (!(part instanceof TextPart text)) throw new IllegalArgumentException("only text is supported");
                var value = new JsonObject();
                value.addProperty("type", "text");
                value.addProperty("text", text.text());
                parts.add(value);
            }
            object.add("parts", parts);
            array.add(object);
        }
        return array.toString();
    }

    static List<Message> messages(String json) {
        var result = new ArrayList<Message>();
        for (var element : array(json)) {
            var object = element.getAsJsonObject();
            fields(object, Set.of("role", "parts"));
            var parts = new ArrayList<ContentPart>();
            for (var part : object.getAsJsonArray("parts")) {
                var value = part.getAsJsonObject();
                fields(value, Set.of("type", "text"));
                if (!"text".equals(string(value, "type"))) throw new IllegalArgumentException("unknown content type");
                parts.add(new TextPart(string(value, "text")));
            }
            result.add(new Message(MessageRole.valueOf(string(object, "role")), parts));
        }
        return List.copyOf(result);
    }

    static String options(ModelOptions options) {
        var object = new JsonObject();
        object.addProperty("temperature", options.temperature());
        object.addProperty("maxOutputTokens", options.maxOutputTokens());
        return object.toString();
    }

    static ModelOptions options(String json) {
        var object = JsonParser.parseString(json).getAsJsonObject();
        fields(object, Set.of("temperature", "maxOutputTokens"));
        return new ModelOptions(isNull(object, "temperature") ? null : object.get("temperature").getAsDouble(),
                isNull(object, "maxOutputTokens") ? null : object.get("maxOutputTokens").getAsBigDecimal().intValueExact());
    }

    static String history(List<ContextHistoryRef> history) {
        var array = new JsonArray();
        for (var ref : history) {
            var object = new JsonObject();
            object.addProperty("turnId", ref.turnId());
            object.addProperty("turnVersion", ref.turnVersion());
            object.addProperty("executionId", ref.executionId());
            array.add(object);
        }
        return array.toString();
    }

    static List<ContextHistoryRef> history(String json) {
        var result = new ArrayList<ContextHistoryRef>();
        for (var element : array(json)) {
            var object = element.getAsJsonObject();
            fields(object, Set.of("turnId", "turnVersion", "executionId"));
            result.add(new ContextHistoryRef(string(object, "turnId"), object.get("turnVersion").getAsBigDecimal().longValueExact(), string(object, "executionId")));
        }
        return List.copyOf(result);
    }

    static String resources(List<ResourceRef> resources) {
        var array = new JsonArray();
        for (var ref : resources) {
            var object = new JsonObject();
            object.addProperty("resourceType", ref.resourceType());
            object.addProperty("resourceId", ref.resourceId());
            object.addProperty("version", ref.version());
            object.addProperty("draftId", ref.draftId());
            object.addProperty("rangeRef", ref.rangeRef());
            object.addProperty("contentDigest", ref.contentDigest());
            array.add(object);
        }
        return array.toString();
    }

    static List<ResourceRef> resources(String json) {
        var result = new ArrayList<ResourceRef>();
        for (var element : array(json)) {
            var object = element.getAsJsonObject();
            fields(object, Set.of("resourceType", "resourceId", "version", "draftId", "rangeRef", "contentDigest"));
            result.add(new ResourceRef(string(object, "resourceType"), string(object, "resourceId"), optional(object, "version"), optional(object, "draftId"), optional(object, "rangeRef"), optional(object, "contentDigest")));
        }
        return List.copyOf(result);
    }

    static boolean emptyArray(String json) {
        return array(json).isEmpty();
    }

    private static JsonArray array(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static boolean isNull(JsonObject object, String key) {
        return !object.has(key) || object.get(key).isJsonNull();
    }

    private static String optional(JsonObject object, String key) {
        return isNull(object, key) ? null : string(object, key);
    }

    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("invalid stored string field");
        return value.getAsString();
    }

    private static void fields(JsonObject object, Set<String> names) {
        if (!names.containsAll(object.keySet())) throw new IllegalArgumentException("unknown stored fields");
    }
}
