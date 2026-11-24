package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantUnavailableException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/** Ошибки — в формате ProblemDetail (RFC 9457), без стектрейсов наружу. */
@RestControllerAdvice
class ApiExceptionHandler {

    /** Заглушки скелета: «Задание N: …» — видно, что осталось сделать. */
    @ExceptionHandler(UnsupportedOperationException.class)
    ProblemDetail notImplemented(UnsupportedOperationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
    }

    /** The model provider failed: 502 with a generic message, the provider's response body stays in the log. */
    @ExceptionHandler(AssistantUnavailableException.class)
    ProblemDetail assistantUnavailable(AssistantUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Например, подтверждение заявки, которая уже не PENDING_CONFIRMATION, если решишь отвечать так. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
