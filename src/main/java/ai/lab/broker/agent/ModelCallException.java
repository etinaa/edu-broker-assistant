package ai.lab.broker.agent;

/** The request to the model failed (network, provider error, unreadable reply); the cause has the details. */
public class ModelCallException extends RuntimeException {

    ModelCallException(Throwable cause) {
        super(cause);
    }
}
