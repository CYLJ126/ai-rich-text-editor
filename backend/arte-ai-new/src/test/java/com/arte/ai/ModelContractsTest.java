package com.arte.ai;

import com.arte.ai.model.budget.Usage;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.ContentPart;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ModelContractsTest {
    @Test
    public void mutableInputCannotChangePreparedMessages() {
        var parts = new ArrayList<ContentPart>();
        parts.add(new TextPart("question"));
        var message = new Message(MessageRole.USER, parts);
        var messages = new ArrayList<Message>();
        messages.add(message);
        var request = new GenerationRequest(messages, new ModelOptions(null, 100), List.of(), null);
        parts.clear();
        messages.clear();
        assertEquals("question", ((TextPart) request.messages().getFirst().parts().getFirst()).text());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
    }

    @Test
    public void unknownUsageIsDistinctFromZeroAndMalformedOptionsFail() {
        assertNull(Usage.unknown().inputTokens());
        assertNull(Usage.unknown().reportedCost());
        assertThrows(IllegalArgumentException.class, () -> new Usage(-1L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelOptions(Double.NaN, 10));
        assertThrows(IllegalArgumentException.class, () -> new ModelOptions(1.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef("model", "default", "latest"));
    }
}
