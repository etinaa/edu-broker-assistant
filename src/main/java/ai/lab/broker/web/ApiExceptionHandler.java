package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantUnavailableException;
import ai.lab.broker.agent.SessionBusyException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Errors as ProblemDetail (RFC 9457), with no stack traces sent to the client. {@link #problemFor} is the one
 * mapping from an exception to a status: /chat/stream uses it too, for the error event of a stream.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    /** What the client gets for a bug in our code: no internal text. */
    static final String INTERNAL_ERROR = "The request failed, please try again";

    @ExceptionHandler({UnsupportedOperationException.class, AssistantUnavailableException.class,
            SessionBusyException.class, NoSuchElementException.class, OrderRefusedException.class,
            StreamsFullException.class})
    ProblemDetail handle(Exception e) {
        return problemFor(e);
    }

    /**
     * <ul>
     *   <li>skeleton stubs ("Task N: ...") — 501: shows what is left to implement;</li>
     *   <li>the model provider failed — 502 with a generic message, the provider's response body stays in the log;</li>
     *   <li>another message of the session is being answered — 409, the client retries after it;</li>
     *   <li>an unknown account, order or trace — 404;</li>
     *   <li>BrokerService refused to confirm or reject — 409 with the reason text and its code (PRICE_CHANGED,
     *       ORDER_EXPIRED, …);</li>
     *   <li>too many streams are running — 503, the client retries later;</li>
     *   <li>anything else is a bug — 500 without a message, so internal text does not leak and a bug does not look
     *       like a conflict. For /chat Spring answers such exceptions itself, since they are not handled here.</li>
     * </ul>
     */
    static ProblemDetail problemFor(Throwable e) {
        return switch (e) {
            case UnsupportedOperationException u -> problem(HttpStatus.NOT_IMPLEMENTED, u.getMessage());
            case AssistantUnavailableException u -> problem(HttpStatus.BAD_GATEWAY, u.getMessage());
            case SessionBusyException b -> problem(HttpStatus.CONFLICT, b.getMessage());
            case NoSuchElementException n -> problem(HttpStatus.NOT_FOUND, n.getMessage());
            case OrderRefusedException r -> {
                ProblemDetail problem = problem(HttpStatus.CONFLICT, r.getMessage());
                problem.setProperty("reason", r.refusal().reason());
                yield problem;
            }
            case StreamsFullException f -> problem(HttpStatus.SERVICE_UNAVAILABLE, f.getMessage());
            default -> problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
        };
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
