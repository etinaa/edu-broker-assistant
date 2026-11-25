package ai.lab.broker.domain;

/** Lifecycle of an order: created by the agent, then decided by a human. */
public enum OrderStatus {
    /** Created by the agent and waiting for the user's decision. Cash and positions are untouched. */
    PENDING_CONFIRMATION,
    /** Confirmed and executed: cash and the position have changed. */
    EXECUTED,
    /** Rejected by the user. */
    REJECTED_BY_USER,
    /**
     * Cancelled by the system, not by the user: the chat request that created it failed, so the user never saw it
     * and a retry would create it again.
     */
    CANCELLED,
    /**
     * Confirmed, but the re-check at confirm time failed: not enough cash or securities any more, or the price moved
     * against the client by more than broker.max-price-move-percent.
     */
    FAILED,
    /** Not confirmed within broker.order-ttl (task 6c). */
    EXPIRED
}
