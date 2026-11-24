package ai.lab.broker.web;

import ai.lab.broker.agent.Trace;
import ai.lab.broker.agent.TraceStore;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
class TraceController {

    private final TraceStore traces;

    TraceController(TraceStore traces) {
        this.traces = traces;
    }

    @GetMapping("/traces/{id}")
    Trace trace(@PathVariable String id) {
        return traces.find(id).orElseThrow(() -> new NoSuchElementException("Trace " + id + " not found"));
    }
}
