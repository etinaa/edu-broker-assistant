package ai.lab.broker.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads the server-sent events a MockMvc request of /chat/stream wrote, waiting for the last one. */
final class SseEvents {

    record Event(String name, JsonNode data) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long WAIT_MS = 5_000;

    private SseEvents() {
    }

    /** Waits until the stream has its final event (response or error), then returns all events in order. */
    static List<Event> await(MvcResult result) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            List<Event> events = parse(result.getResponse().getContentAsString());
            boolean finished = !events.isEmpty()
                    && List.of("response", "error").contains(events.getLast().name());
            if (finished) {
                return events;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("The stream did not finish: " + events);
            }
            Thread.sleep(10);
        }
    }

    static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).toList();
    }

    private static List<Event> parse(String body) throws IOException {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length());
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            if (name != null) {
                events.add(new Event(name, JSON.readTree(data.toString())));
            }
        }
        return events;
    }
}
