package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.agent.AssistantUnavailableException;
import ai.lab.broker.agent.ChatListener;
import ai.lab.broker.agent.SessionBusyException;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.web.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(ChatStreams.class)
class ChatControllerTest {

    private static final String BODY = "{\"sessionId\":\"s1\",\"message\":\"hi\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AssistantService assistant;

    @MockitoBean
    private BrokerService broker;

    @Test
    void chatPassesAccountFromHeaderToAssistant() throws Exception {
        when(broker.accountExists("BRK-2")).thenReturn(true);
        when(assistant.chat("BRK-2", "s1", "hi"))
                .thenReturn(new ChatResponse("ok", null, List.of(), List.of(), false, "t1"));

        mvc.perform(post("/chat").header("X-Account-Id", "BRK-2")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("ok"));
    }

    @Test
    void chatForUnknownAccountIs404AndModelIsNotCalled() throws Exception {
        when(broker.accountExists("NOPE")).thenReturn(false);

        mvc.perform(post("/chat").header("X-Account-Id", "NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());

        verify(assistant, never()).chat(any(), any(), any());
    }

    @Test
    void busySessionIs409() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);
        when(assistant.chat("BRK-1", "s1", "hi")).thenThrow(new SessionBusyException());

        mvc.perform(post("/chat").header("X-Account-Id", "BRK-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("still being processed")));
    }

    @Test
    void chatWithoutAccountHeaderIs400() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetSessionIsScopedToAccount() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);

        mvc.perform(delete("/chat/s1").header("X-Account-Id", "BRK-1"))
                .andExpect(status().isNoContent());

        verify(assistant).resetSession("BRK-1", "s1");
    }

    @Test
    void tooLongOrUnusualSessionIdIs400AndModelIsNotCalled() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);

        for (String sessionId : new String[]{"x".repeat(65), "has space", "../etc", "s\\n1"}) {
            String body = "{\"sessionId\":\"" + sessionId + "\",\"message\":\"hi\"}";
            mvc.perform(post("/chat").header("X-Account-Id", "BRK-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(assistant, never()).chat(any(), any(), any());
    }

    @Test
    void sessionIdOfLettersDigitsDashAndUnderscoreIsAccepted() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);
        when(assistant.chat("BRK-1", "web_Tab-1", "hi"))
                .thenReturn(new ChatResponse("ok", null, List.of(), List.of(), false, "t1"));

        mvc.perform(post("/chat").header("X-Account-Id", "BRK-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web_Tab-1\",\"message\":\"hi\"}"))
                .andExpect(status().isOk());
    }

    // ---- /chat/stream ----

    private SseEvents.Event streamFinalEvent(String accountId) throws Exception {
        MvcResult result = mvc.perform(post("/chat/stream").header("X-Account-Id", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();
        return SseEvents.await(result).getLast();
    }

    @Test
    void streamReportsToolCallsAndEndsWithTheResponse() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);
        when(assistant.chat(eq("BRK-1"), eq("s1"), eq("hi"), any(ChatListener.class))).thenAnswer(invocation -> {
            ChatListener listener = invocation.getArgument(3);
            ToolCall call = new ToolCall("c1", "get_quote", new ObjectMapper().readTree("{\"ticker\":\"TRNC\"}"));
            listener.toolCall(call);
            listener.toolResult(ToolResult.ok(call, "{\"price\":185.25,\"secret\":\"internal\"}"));
            return new ChatResponse("ok", null, List.of(), List.of(), false, "t1");
        });

        MvcResult result = mvc.perform(post("/chat/stream").header("X-Account-Id", "BRK-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();
        List<SseEvents.Event> events = SseEvents.await(result);

        assertEquals(List.of("tool_call", "tool_result", "response"), SseEvents.names(events));
        assertEquals("get_quote", events.get(0).data().path("tool").asText());
        assertFalse(events.get(0).data().has("arguments"), "arguments only once the guards have decided");
        assertEquals("TRNC", events.get(1).data().path("arguments").path("ticker").asText());
        assertFalse(events.get(1).data().path("error").asBoolean());
        assertFalse(result.getResponse().getContentAsString().contains("internal"), "tool results are not streamed");
        assertEquals("ok", events.get(2).data().path("answer").asText());
        assertEquals("t1", events.get(2).data().path("traceId").asText());
    }

    @Test
    void streamForUnknownAccountIs404BeforeTheStreamStarts() throws Exception {
        when(broker.accountExists("NOPE")).thenReturn(false);

        mvc.perform(post("/chat/stream").header("X-Account-Id", "NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());

        verify(assistant, never()).chat(any(), any(), any(), any());
    }

    @Test
    void streamWithInvalidBodyIs400() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);

        mvc.perform(post("/chat/stream").header("X-Account-Id", "BRK-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void busySessionInAStreamIsAnErrorEventWith409() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);
        when(assistant.chat(any(), any(), any(), any())).thenThrow(new SessionBusyException());

        SseEvents.Event last = streamFinalEvent("BRK-1");

        assertEquals("error", last.name());
        assertEquals(409, last.data().path("status").asInt());
        assertTrue(last.data().path("detail").asText().contains("still being processed"));
    }

    @Test
    void providerFailureInAStreamIsAnErrorEventWith502() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);
        when(assistant.chat(any(), any(), any(), any()))
                .thenThrow(new AssistantUnavailableException("The model is unavailable", new RuntimeException()));

        SseEvents.Event last = streamFinalEvent("BRK-1");

        assertEquals(502, last.data().path("status").asInt());
    }

    @Test
    void aBugInAStreamIsAnErrorEventWith500WithoutInternalText() throws Exception {
        when(broker.accountExists("BRK-1")).thenReturn(true);
        when(assistant.chat(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db.internal password=secret"));

        SseEvents.Event last = streamFinalEvent("BRK-1");

        assertEquals(500, last.data().path("status").asInt());
        assertEquals(ApiExceptionHandler.INTERNAL_ERROR, last.data().path("detail").asText());
        assertFalse(last.data().toString().contains("secret"));
    }
}
