package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.web.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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
        when(broker.accountById("BRK-2")).thenReturn(Optional.of(new Account("BRK-2", new BigDecimal("100.00"))));
        when(assistant.chat("BRK-2", "s1", "hi"))
                .thenReturn(new ChatResponse("ok", null, List.of(), false, "t1"));

        mvc.perform(post("/chat").header("X-Account-Id", "BRK-2")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("ok"));
    }

    @Test
    void chatForUnknownAccountIs404AndModelIsNotCalled() throws Exception {
        when(broker.accountById("NOPE")).thenReturn(Optional.empty());

        mvc.perform(post("/chat").header("X-Account-Id", "NOPE")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());

        verify(assistant, never()).chat(any(), any(), any());
    }

    @Test
    void chatWithoutAccountHeaderIs400() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetSessionIsScopedToAccount() throws Exception {
        when(broker.accountById("BRK-1")).thenReturn(Optional.of(new Account("BRK-1", BigDecimal.TEN)));

        mvc.perform(delete("/chat/s1").header("X-Account-Id", "BRK-1"))
                .andExpect(status().isNoContent());

        verify(assistant).resetSession("BRK-1", "s1");
    }
}
