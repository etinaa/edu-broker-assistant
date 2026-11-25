package ai.lab.broker.agent;

/**
 * Another message of the same session is still being answered (the model can take minutes). The request is refused
 * instead of holding a server thread in a queue; the client retries after the answer it is waiting for.
 */
public class SessionBusyException extends RuntimeException {

    public SessionBusyException() {
        super("Another message of this session is still being processed, please try again when it is answered");
    }
}
