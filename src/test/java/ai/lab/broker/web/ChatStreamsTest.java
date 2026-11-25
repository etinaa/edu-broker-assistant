package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.web.dto.ChatResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamsTest {

    private static final ChatResponse OK = new ChatResponse("ok", null, List.of(), List.of(), false, "t1");

    @Test
    void aStreamAboveTheLimitIsRefusedAndTheSlotIsFreedWhenAStreamEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AssistantService assistant = mock(AssistantService.class);
        when(assistant.chat(any(), any(), any(), any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return OK;
        });
        ChatStreams streams = new ChatStreams(assistant, Duration.ofMinutes(1), 1);
        try {
            streams.start("BRK-1", "s1", "hi");
            verify(assistant, timeout(1000)).chat(any(), any(), any(), any());

            assertThrows(StreamsFullException.class, () -> streams.start("BRK-1", "s2", "hi"));

            release.countDown();
            long deadline = System.currentTimeMillis() + 5_000;
            while (true) {
                try {
                    streams.start("BRK-1", "s3", "hi");
                    break;
                } catch (StreamsFullException e) {
                    assertTrue(System.currentTimeMillis() < deadline, "the slot was not freed");
                    Thread.sleep(10);
                }
            }
            verify(assistant, timeout(1000).times(2)).chat(any(), any(), any(), any());
        } finally {
            streams.destroy();
        }
    }

    @Test
    void aStreamAfterShutdownIsRefused() {
        ChatStreams streams = new ChatStreams(mock(AssistantService.class), Duration.ofMinutes(1), 5);
        streams.destroy();

        assertThrows(StreamsFullException.class, () -> streams.start("BRK-1", "s1", "hi"));
    }

    @Test
    void aFullServerIs503() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(),
                ApiExceptionHandler.problemFor(new StreamsFullException()).getStatus());
    }

    @Test
    void nonPositiveMaxStreamsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChatStreams(mock(AssistantService.class), Duration.ofMinutes(1), 0));
    }
}
