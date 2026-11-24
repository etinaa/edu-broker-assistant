package ai.lab.broker.domain;

/** Lifecycle of an order: created by the agent, then decided by a human. */
public enum OrderStatus {
    /** Created by the agent and waiting for the user's decision. Cash and positions are untouched. */
    PENDING_CONFIRMATION,
    /** Confirmed and executed: cash and the position have changed. */
    EXECUTED,
    /** Rejected by the user. */
    REJECTED_BY_USER,
    /** Confirmed, but the re-check at confirm time failed (not enough cash or securities any more). */
    FAILED,
    /** Not confirmed in time (task 6c). */
    EXPIRED
}
