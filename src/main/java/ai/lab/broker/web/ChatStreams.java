package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.agent.ChatListener;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import ai.lab.broker.web.dto.ChatResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * POST /chat/stream (task 10): the same message handling as /chat, with progress sent as server-sent events while
 * the model works. Events, in order:
 * <ul>
 *   <li>{@code tool_call} — {tool}: the model asked for a call, before it runs. Without the arguments: the guards
 *       may still refuse the call, and a UI must not show "BUY 10 TRNC" for an order that will never exist;</li>
 *   <li>{@code tool_result} — {tool, arguments, error}: the call is done, or was refused ({@code error: true}).
 *       The result itself is not sent: it holds order ids and account data of a message that may still fail and
 *       be rolled back, and the response has what the client needs;</li>
 *   <li>{@code response} — the {@link ChatResponse}, exactly what /chat returns; the stream ends after it;</li>
 *   <li>{@code error} — a ProblemDetail instead of the response, mapped like /chat errors
 *       ({@link ApiExceptionHandler#problemFor}): 409 the session is busy, 502 the model is unavailable, 500 a bug
 *       (no details); the stream ends after it.</li>
 * </ul>
 * The request is answered on a virtual thread, so a slow model holds no request thread. That also removes the limit
 * the request thread pool puts on /chat, so at most {@code agent.max-streams} streams run at once; one more is a 503
 * before the stream starts. The HTTP status is already 200 when the session lock is taken, so a busy session is an
 * error event, not a 409 response. A client that goes away does not cancel the message: like a dropped /chat, it is
 * answered and stays in the session history.
 */
@Slf4j
@Component
class ChatStreams implements DisposableBean {

    /**
     * Tool arguments are Jackson 2 trees (the LLM clients), while Spring MVC writes events with Jackson 3, which does
     * not know them; as plain maps and lists they serialize the same with either.
     */
    private static final ObjectMapper ARGUMENTS = new ObjectMapper();

    static final String TOOL_CALL = "tool_call";
    static final String TOOL_RESULT = "tool_result";
    static final String RESPONSE = "response";
    static final String ERROR = "error";

    private final AssistantService assistant;
    private final Duration timeout;
    private final Semaphore running;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ChatStreams(AssistantService assistant, @Value("${agent.stream-timeout:5m}") Duration timeout,
                @Value("${agent.max-streams:50}") int maxStreams) {
        if (maxStreams <= 0) {
            throw new IllegalArgumentException("agent.max-streams must be positive: " + maxStreams);
        }
        this.assistant = assistant;
        this.timeout = timeout;
        this.running = new Semaphore(maxStreams);
    }

    /** @throws StreamsFullException agent.max-streams streams are running, or the server is shutting down */
    SseEmitter start(String accountId, String sessionId, String message) {
        if (!running.tryAcquire()) {
            throw new StreamsFullException();
        }
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        Events events = new Events(emitter);
        emitter.onCompletion(events::close);
        emitter.onTimeout(events::close);
        emitter.onError(e -> events.close());
        try {
            executor.execute(() -> answerAndRelease(accountId, sessionId, message, events));
        } catch (RejectedExecutionException e) {
            running.release();
            throw new StreamsFullException();
        }
        return emitter;
    }

    private void answerAndRelease(String accountId, String sessionId, String message, Events events) {
        try {
            answer(accountId, sessionId, message, events);
        } finally {
            running.release();
        }
    }

    /**
     * The response is sent outside the try: once chat() returned, the message is in the history and its order is
     * pending, so a failure to send it must not be reported as a failed message the client would retry.
     */
    private void answer(String accountId, String sessionId, String message, Events events) {
        ChatResponse response;
        try {
            response = assistant.chat(accountId, sessionId, message, events);
        } catch (RuntimeException | Error e) {
            ProblemDetail problem = ApiExceptionHandler.problemFor(e);
            if (problem.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                // A bug in our code: logged with the stack trace, the client gets no internal text.
                log.error("Streamed chat failed for session {}", sessionId, e);
            }
            events.finish(ERROR, problem);
            return;
        }
        events.finish(RESPONSE, response);
    }

    /** Stops waiting for running streams on shutdown: their clients get a closed connection. */
    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * The events of one stream. Never throws, as {@link ChatListener} requires: a failure to build or send an event
     * (the client is gone, the emitter timed out, serialization failed) only stops the events of this stream.
     */
    private static final class Events implements ChatListener {

        private final SseEmitter emitter;
        private volatile boolean closed;

        Events(SseEmitter emitter) {
            this.emitter = emitter;
        }

        record CallEvent(String tool) {}

        record ResultEvent(String tool, Object arguments, boolean error) {}

        @Override
        public void toolCall(ToolCall call) {
            send(TOOL_CALL, () -> new CallEvent(call.name()));
        }

        @Override
        public void toolResult(ToolResult result) {
            send(TOOL_RESULT, () -> new ResultEvent(result.call().name(),
                    ARGUMENTS.convertValue(result.call().arguments(), Object.class), result.error()));
        }

        /** The last event; then the stream is completed. */
        void finish(String name, Object data) {
            send(name, () -> data);
            if (closed) {
                return;
            }
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Could not complete a chat stream", e);
            }
        }

        void close() {
            closed = true;
        }

        /** data is built here, inside the try: converting the tool arguments may fail too. */
        private void send(String name, Supplier<Object> data) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(data.get(), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                // The client disconnected: an ordinary end of a stream, nothing to log.
                stop(e);
            } catch (RuntimeException e) {
                log.warn("Could not send the {} event of a chat stream", name, e);
                stop(e);
            }
        }

        /** Nobody gets this stream any more: no more events, and the connection is closed if it is still open. */
        private void stop(Exception cause) {
            closed = true;
            try {
                emitter.completeWithError(cause);
            } catch (RuntimeException alreadyCompleted) {
                // Nothing left to close.
            }
        }
    }
}
