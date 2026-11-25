package ai.lab.broker.web;

import ai.lab.broker.domain.OrderDecision;

/** BrokerService refused to confirm or reject an order: a business outcome, answered with 409 and the reason. */
class OrderRefusedException extends RuntimeException {

    private final OrderDecision.Refused refusal;

    OrderRefusedException(OrderDecision.Refused refusal) {
        super(refusal.message());
        this.refusal = refusal;
    }

    OrderDecision.Refused refusal() {
        return refusal;
    }
}
