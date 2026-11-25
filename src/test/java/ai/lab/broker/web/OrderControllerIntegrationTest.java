package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application context with the real BrokerService / OrderBook / AccountRepository.
 * Demo data: BRK-1 cash 10000.00, TRNC=20; BRK-2 cash 100.00, SMPL=10.
 * The context is shared; {@link DemoState} resets the in-memory state before each test.
 */
@SpringBootTest(properties = "llm.provider=ollama")
@AutoConfigureMockMvc
class OrderControllerIntegrationTest {

    private static final String HEADER = "X-Account-Id";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrokerService broker;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        DemoState.reset(context);
    }

    private String place(String accountId, OrderSide side, String ticker, long qty) {
        OrderDecision d = broker.placeOrder(accountId, side, ticker, qty);
        return assertInstanceOf(OrderDecision.Created.class, d).order().getId();
    }

    private Account account(String id) {
        return broker.accountSnapshot(id).orElseThrow();
    }

    @Test
    void getReturnsOwnPendingOrder() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 2);

        mvc.perform(get("/orders/" + id).header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.ticker").value("TRNC"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
    }

    @Test
    void getUnknownOrderIs404() throws Exception {
        mvc.perform(get("/orders/ORD-999").header(HEADER, "BRK-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getForeignOrderIs404() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 1);

        mvc.perform(get("/orders/" + id).header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingHeaderIs400() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 1);

        mvc.perform(get("/orders/" + id)).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/" + id + "/confirm")).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/" + id + "/reject")).andExpect(status().isBadRequest());
    }

    @Test
    void confirmBuyExecutesOrderAndMovesMoneyAndShares() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 10);

        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertEquals(0, new BigDecimal("8147.50").compareTo(account("BRK-1").getCash()));
        assertEquals(30L, account("BRK-1").quantityByTicker("TRNC"));
    }

    @Test
    void confirmSellExecutesOrder() throws Exception {
        String id = place("BRK-1", OrderSide.SELL, "TRNC", 4);

        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertEquals(0, new BigDecimal("10741.00").compareTo(account("BRK-1").getCash()));
        assertEquals(16L, account("BRK-1").quantityByTicker("TRNC"));
    }

    @Test
    void secondConfirmIs409AndAccountChangedOnlyOnce() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 10);
        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-1")).andExpect(status().isOk());

        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isConflict());

        assertEquals(0, new BigDecimal("8147.50").compareTo(account("BRK-1").getCash()));
        assertEquals(30L, account("BRK-1").quantityByTicker("TRNC"));
    }

    @Test
    void confirmIs409WhenFundsNoLongerSufficient() throws Exception {
        // BRK-2 has 100.00; two pending buys of 8 x SMPL (98.40 each) both pass placement.
        String first = place("BRK-2", OrderSide.BUY, "SMPL", 8);
        String second = place("BRK-2", OrderSide.BUY, "SMPL", 8);
        mvc.perform(post("/orders/" + first + "/confirm").header(HEADER, "BRK-2")).andExpect(status().isOk());

        mvc.perform(post("/orders/" + second + "/confirm").header(HEADER, "BRK-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));

        assertEquals(18L, account("BRK-2").quantityByTicker("SMPL"));
    }

    @Test
    void confirmForeignOrderIs404AndNothingChanges() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 1);

        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound());

        assertEquals(0, new BigDecimal("10000.00").compareTo(account("BRK-1").getCash()));
        mvc.perform(get("/orders/" + id).header(HEADER, "BRK-1"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
    }

    @Test
    void rejectMarksOrderRejectedWithoutMovingMoney() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 10);

        mvc.perform(post("/orders/" + id + "/reject").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_BY_USER"));

        assertEquals(0, new BigDecimal("10000.00").compareTo(account("BRK-1").getCash()));
        assertEquals(20L, account("BRK-1").quantityByTicker("TRNC"));
    }

    @Test
    void confirmAfterRejectIs409() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 1);
        mvc.perform(post("/orders/" + id + "/reject").header(HEADER, "BRK-1")).andExpect(status().isOk());

        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isConflict());

        assertEquals(0, new BigDecimal("10000.00").compareTo(account("BRK-1").getCash()));
    }

    @Test
    void rejectAfterConfirmIs409AndOrderStaysExecuted() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 1);
        mvc.perform(post("/orders/" + id + "/confirm").header(HEADER, "BRK-1")).andExpect(status().isOk());

        mvc.perform(post("/orders/" + id + "/reject").header(HEADER, "BRK-1"))
                .andExpect(status().isConflict());

        mvc.perform(get("/orders/" + id).header(HEADER, "BRK-1"))
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void rejectForeignOrderIs404() throws Exception {
        String id = place("BRK-1", OrderSide.BUY, "TRNC", 1);

        mvc.perform(post("/orders/" + id + "/reject").header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound());
    }
}
