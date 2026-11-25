package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String HEADER = "X-Account-Id";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BrokerService broker;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order("ORD-1", "BRK-1", OrderSide.BUY, "TRNC", 2, new BigDecimal("185.25"), Instant.now());
        when(broker.orderById("ORD-1")).thenReturn(Optional.of(order));
    }

    @Test
    void getOrderReturnsOrderOfHeaderAccount() throws Exception {
        mvc.perform(get("/orders/ORD-1").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ORD-1"))
                .andExpect(jsonPath("$.accountId").value("BRK-1"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
    }

    @Test
    void getOrderOfAnotherAccountIsNotFound() throws Exception {
        mvc.perform(get("/orders/ORD-1").header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnknownOrderIsNotFound() throws Exception {
        when(broker.orderById("ORD-9")).thenReturn(Optional.empty());

        mvc.perform(get("/orders/ORD-9").header(HEADER, "BRK-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingHeaderIsBadRequest() throws Exception {
        mvc.perform(get("/orders/ORD-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/ORD-1/confirm")).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/ORD-1/reject")).andExpect(status().isBadRequest());
    }

    @Test
    void confirmPassesHeaderAccountToService() throws Exception {
        order.setStatus(OrderStatus.EXECUTED, "Order confirmed");
        when(broker.confirmOrder("BRK-1", "ORD-1")).thenReturn(new OrderDecision.Confirmed(order));

        mvc.perform(post("/orders/ORD-1/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void confirmRefusedByServiceIsConflict() throws Exception {
        when(broker.confirmOrder("BRK-1", "ORD-1")).thenReturn(
                new OrderDecision.Refused(OrderDecision.Reason.INSUFFICIENT_FUNDS, "Insufficient funds"));

        mvc.perform(post("/orders/ORD-1/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient funds"));
    }

    @Test
    void confirmOfAnotherAccountsOrderIsNotFoundAndServiceNotCalled() throws Exception {
        mvc.perform(post("/orders/ORD-1/confirm").header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound());

        verify(broker, never()).confirmOrder(any(), any());
    }

    @Test
    void rejectPassesHeaderAccountToService() throws Exception {
        order.setStatus(OrderStatus.REJECTED_BY_USER, "Order cancelled by user");
        when(broker.rejectOrder("BRK-1", "ORD-1")).thenReturn(new OrderDecision.Rejected(order));

        mvc.perform(post("/orders/ORD-1/reject").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_BY_USER"));
    }

    @Test
    void rejectRefusedByServiceIsConflict() throws Exception {
        when(broker.rejectOrder("BRK-1", "ORD-1")).thenReturn(
                new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS, "Can't reject order"));

        mvc.perform(post("/orders/ORD-1/reject").header(HEADER, "BRK-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectOfAnotherAccountsOrderIsNotFoundAndServiceNotCalled() throws Exception {
        mvc.perform(post("/orders/ORD-1/reject").header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound());

        verify(broker, never()).rejectOrder(any(), any());
    }
}
