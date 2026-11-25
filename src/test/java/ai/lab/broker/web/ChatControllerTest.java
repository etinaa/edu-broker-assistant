package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.agent.SessionBusyException;
import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.web.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
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
                .thenReturn(new ChatResponse("ok", null, List.of(), false, "t1"));

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
                .thenReturn(new ChatResponse("ok", null, List.of(), false, "t1"));

        mvc.perform(post("/chat").header("X-Account-Id", "BRK-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"web_Tab-1\",\"message\":\"hi\"}"))
                .andExpect(status().isOk());
    }
}
