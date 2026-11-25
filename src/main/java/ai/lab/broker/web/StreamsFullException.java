package ai.lab.broker.web;

/** As many /chat/stream requests as agent.max-streams are being answered, or the server is shutting down: 503. */
class StreamsFullException extends RuntimeException {

    StreamsFullException() {
        super("Too many chat streams are running right now, please try again later");
    }
}
