package ai.lab.broker.web;

import ai.lab.broker.agent.Trace;
import ai.lab.broker.agent.TraceStore;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
class TraceController {

    private final TraceStore traces;

    TraceController(TraceStore traces) {
        this.traces = traces;
    }

    /**
     * Only the owner's account gets its trace. A foreign trace is 404, not 403, so trace ids cannot be probed
     * for existence.
     */
    @GetMapping("/traces/{id}")
    Trace trace(@RequestHeader(ChatController.ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        return traces.find(id)
                .filter(trace -> trace.accountId().equals(accountId))
                .orElseThrow(() -> new NoSuchElementException("Trace " + id + " not found"));
    }
}
