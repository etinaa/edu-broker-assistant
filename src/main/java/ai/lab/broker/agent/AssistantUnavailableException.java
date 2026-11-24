package ai.lab.broker.agent;

/**
 * The model provider failed (network, 429, 5xx, malformed response). The message is safe to show to the client;
 * the provider's details stay in the cause and in the log.
 */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
