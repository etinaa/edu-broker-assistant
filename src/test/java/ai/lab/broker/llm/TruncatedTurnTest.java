package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each provider marks a reply cut off by the output limit with its own stop reason. */
class TruncatedTurnTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static LlmResponse stoppedBy(String stopReason) {
        return new LlmResponse("partial", 10, 1024, 1, stopReason, "0");
    }

    private static boolean claudeTruncated(String stopReason) throws Exception {
        ClaudeClient client = mock(ClaudeClient.class);
        ArrayNode content = JSON.createArrayNode();
        content.addObject().put("type", "text").put("text", "partial");
        when(client.chatWithTools(anyString(), any(), any(), any(), anyDouble()))
                .thenReturn(new ClaudeClient.ToolTurn(content, stoppedBy(stopReason)));
        ClaudeToolConversation conversation =
                new ClaudeToolConversation(client, "system", JSON.createArrayNode(), null, 0.0);
        conversation.addUserMessage("hi");
        return conversation.send().truncated();
    }

    private static boolean ollamaTruncated(String doneReason) throws Exception {
        OllamaClient client = mock(OllamaClient.class);
        when(client.chatWithTools(anyString(), any(), any(), anyDouble())).thenReturn(new OllamaClient.ToolTurn(
                JSON.createObjectNode().put("role", "assistant").put("content", "partial"), stoppedBy(doneReason)));
        OllamaToolConversation conversation =
                new OllamaToolConversation(client, "system", JSON.createArrayNode(), 0.0);
        conversation.addUserMessage("hi");
        return conversation.send().truncated();
    }

    @Test
    void claudeMaxTokensIsTruncated() throws Exception {
        assertTrue(claudeTruncated("max_tokens"));
        assertFalse(claudeTruncated("end_turn"));
        assertFalse(claudeTruncated("tool_use"));
    }

    @Test
    void ollamaLengthIsTruncated() throws Exception {
        assertTrue(ollamaTruncated("length"));
        assertFalse(ollamaTruncated("stop"));
    }
}
