package com.arte.app.ainew;

import com.google.gson.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.function.Consumer;

/**
 * 有界 SSE 解码：网络片段可以切在 UTF-8 字符、行及事件中间。只发布 assistant content。
 */
final class ChatCompletionStream {
    private final Consumer<String> deltas;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder(), content = new StringBuilder();
    private JsonElement usage;
    private String finish;
    private boolean done;

    ChatCompletionStream(Consumer<String> deltas) {
        this.deltas = deltas;
    }

    void accept(byte[] bytes, int offset, int length) throws Exception {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] == '\n') {
                byte[] value = line.toByteArray();
                line.reset();
                int size = value.length > 0 && value[value.length - 1] == '\r' ? value.length - 1 : value.length;
                var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                consumeLine(decoder.decode(ByteBuffer.wrap(value, 0, size)).toString());
            } else {
                if (line.size() >= 65536) throw new IllegalArgumentException("model stream line too large");
                line.write(bytes[i]);
            }
        }
    }

    private void consumeLine(String value) {
        if (value.isEmpty()) {
            if (!data.isEmpty()) {
                frame(data.toString());
                data.setLength(0);
            }
        } else if (value.startsWith("data:")) {
            var part = value.substring(5);
            if (part.startsWith(" ")) part = part.substring(1);
            if (data.length() + part.length() > 65536)
                throw new IllegalArgumentException("model stream frame too large");
            if (!data.isEmpty()) data.append('\n');
            data.append(part);
        } else if (!value.startsWith(":")) {
            // SSE event/id/retry fields carry no model text.
        }
    }

    private void frame(String value) {
        if (done) throw new IllegalArgumentException("data after model completion");
        if ("[DONE]".equals(value)) {
            if (finish == null) throw new IllegalArgumentException("missing model finish reason");
            done = true;
            return;
        }
        var object = JsonParser.parseString(value).getAsJsonObject();
        if (object.has("error")) throw new IllegalArgumentException("model stream failed");
        var choices = object.getAsJsonArray("choices");
        if (choices == null || choices.size() > 1) throw new IllegalArgumentException("invalid stream choices");
        if (!choices.isEmpty()) {
            var choice = choices.get(0).getAsJsonObject();
            if (choice.has("index") && choice.get("index").getAsBigDecimal().intValueExact() != 0)
                throw new IllegalArgumentException("invalid stream choice index");
            var delta = choice.getAsJsonObject("delta");
            if (delta == null || delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull() && !delta.getAsJsonArray("tool_calls").isEmpty()
                    || delta.has("function_call") && !delta.get("function_call").isJsonNull())
                throw new IllegalArgumentException("unsupported stream output");
            var text = delta.get("content");
            String role = ModelJson.value(delta, "role");
            if (role != null && !"assistant".equals(role)) throw new IllegalArgumentException("invalid streamed role");
            if (text != null && !text.isJsonNull()) {
                if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("invalid streamed text");
                String piece = text.getAsString();
                if (!piece.isEmpty()) {
                    if (finish != null || content.length() + piece.length() > 1048576)
                        throw new IllegalArgumentException("invalid stream ordering or size");
                    content.append(piece);
                    deltas.accept(piece);
                }
            }
            String reason = ModelJson.value(choice, "finish_reason");
            if (reason != null) {
                if (finish != null || !"stop".equals(reason) && !"length".equals(reason))
                    throw new IllegalArgumentException("unsupported stream finish");
                finish = reason;
            }
        } else if (finish == null) throw new IllegalArgumentException("premature usage chunk");
        var reported = object.get("usage");
        if (reported != null && !reported.isJsonNull()) {
            if (usage != null) throw new IllegalArgumentException("duplicate stream usage");
            usage = reported;
        }
    }

    byte[] result() {
        if (!done || line.size() != 0 || !data.isEmpty()) throw new IllegalArgumentException("incomplete model stream");
        var message = new JsonObject();
        message.addProperty("content", content.toString());
        var choice = new JsonObject();
        choice.addProperty("finish_reason", finish);
        choice.add("message", message);
        var choices = new JsonArray();
        choices.add(choice);
        var result = new JsonObject();
        result.add("choices", choices);
        if (usage != null) result.add("usage", usage);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
}
