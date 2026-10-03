package com.arte.app.ainew;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ChatCompletionStreamTest {
    static void feed(ChatCompletionStream stream, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.accept(bytes, 0, bytes.length);
    }

    static String delta(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
    }

    static final String FINISH = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";

    @Test
    void utf8SplitAndUsageOnlyChunkProduceOneResult() throws Exception {
        var received = new ArrayList<String>();
        var stream = new ChatCompletionStream(received::add);
        String input = ": heartbeat\r\n\r\n" + delta("中😀") + FINISH +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) stream.accept(bytes, i, 1);
        assertEquals(java.util.List.of("中😀"), received);
        var result = new String(stream.result(), StandardCharsets.UTF_8);
        assertTrue(result.contains("中😀"));
        assertTrue(result.contains("\"prompt_tokens\":3"));
    }

    @Test
    void multipleDataLinesFormOneSseFrame() throws Exception {
        var received = new ArrayList<String>();
        var stream = new ChatCompletionStream(received::add);
        feed(stream, "data: {\"choices\":\n" + "data: [{\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}\n\n" + FINISH + "data: [DONE]\n\n");
        assertEquals(java.util.List.of("hello"), received);
        assertNotNull(stream.result());
    }

    @Test
    void missingDoneOrFinishCannotPublishSuccessfulResult() throws Exception {
        var stream = new ChatCompletionStream(text -> {
        });
        feed(stream, delta("partial") + FINISH);
        assertThrows(IllegalArgumentException.class, stream::result);
        var missingFinish = new ChatCompletionStream(text -> {
        });
        assertThrows(IllegalArgumentException.class, () -> feed(missingFinish, "data: [DONE]\n\n"));
    }

    @Test
    void unsupportedToolsAndAbnormalFinishAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> feed(new ChatCompletionStream(text -> {
        }), "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{}]},\"finish_reason\":null}]}\n\n"));
        assertThrows(IllegalArgumentException.class, () -> feed(new ChatCompletionStream(text -> {
        }), FINISH.replace("stop", "content_filter")));
    }

    @Test
    void malformedUtf8AndOversizedFramesAreRejected() {
        var stream = new ChatCompletionStream(text -> {
        });
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> stream.accept(new byte[]{(byte) 0xc3, 0x28, '\n'}, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> feed(new ChatCompletionStream(text -> {
        }), "x".repeat(65537)));
    }

    @Test
    void dataAfterDoneAndDuplicateUsageAreRejected() throws Exception {
        var stream = new ChatCompletionStream(text -> {
        });
        feed(stream, FINISH + "data: [DONE]\n\n");
        assertThrows(IllegalArgumentException.class, () -> feed(stream, delta("late")));
        var duplicate = new ChatCompletionStream(text -> {
        });
        String usage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n";
        feed(duplicate, FINISH + usage);
        assertThrows(IllegalArgumentException.class, () -> feed(duplicate, usage));
    }
}
