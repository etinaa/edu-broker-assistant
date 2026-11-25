package ai.lab.broker.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logging of model payloads. Request and response bodies carry the whole conversation and the client's cash and
 * positions, so they go to their own logger, outside the {@code ai.lab.broker} hierarchy: turning
 * {@code ai.lab.broker.llm} (or the whole application) to DEBUG does not dump client data into the logs.
 * Enable {@code llm-payload} explicitly, and only locally.
 */
final class Payloads {

    static final Logger LOG = LoggerFactory.getLogger("llm-payload");

    /** A provider error body is kept for diagnosis, but cut so a large body cannot flood the log. */
    private static final int MAX_ERROR_BODY = 500;

    private Payloads() {
    }

    static String errorBody(String body) {
        if (body == null || body.length() <= MAX_ERROR_BODY) {
            return body;
        }
        return body.substring(0, MAX_ERROR_BODY) + "... (" + body.length() + " chars)";
    }
}
