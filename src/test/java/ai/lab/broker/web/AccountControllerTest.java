package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.SearchResult;
import ai.lab.broker.domain.Security;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    private static final Security TRNC =
            new Security("TRNC", "US0000TRNC07", "Training Corp. common stock", new BigDecimal("185.25"));
    private static final Security TRNP =
            new Security("TRNP", "US0000TRNP02", "Training Corp. preferred stock", new BigDecimal("24.10"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BrokerService broker;

    // ---- GET /accounts/{accountId} ----

    @Test
    void accountReturnsIdCashAndPositions() throws Exception {
        when(broker.accountSnapshot("BRK-1")).thenReturn(Optional.of(
                new Account("BRK-1", new BigDecimal("10000.00"), Map.of("TRNC", 20L))));

        mvc.perform(get("/accounts/BRK-1").header("X-Account-Id", "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("BRK-1"))
                .andExpect(jsonPath("$.cash").value(10000.00))
                .andExpect(jsonPath("$.positions.TRNC").value(20));
    }

    @Test
    void accountReturns404ProblemDetailWhenNotFound() throws Exception {
        when(broker.accountSnapshot("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/accounts/nope").header("X-Account-Id", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Account nope not found"));
    }

    @Test
    void anotherAccountIs404LikeAMissingOne() throws Exception {
        when(broker.accountSnapshot("BRK-1")).thenReturn(Optional.of(
                new Account("BRK-1", new BigDecimal("10000.00"), Map.of("TRNC", 20L))));

        mvc.perform(get("/accounts/BRK-1").header("X-Account-Id", "BRK-2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account BRK-1 not found"))
                .andExpect(jsonPath("$.cash").doesNotExist());
    }

    @Test
    void accountWithoutHeaderIs400() throws Exception {
        mvc.perform(get("/accounts/BRK-1")).andExpect(status().isBadRequest());
    }

    // ---- GET /securities/search ----

    @Test
    void searchReturnsExactMatch() throws Exception {
        when(broker.findSecurity("trnc")).thenReturn(SearchResult.exact(TRNC));

        mvc.perform(get("/securities/search").param("query", "trnc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("EXACT"))
                .andExpect(jsonPath("$.securities.length()").value(1))
                .andExpect(jsonPath("$.securities[0].ticker").value("TRNC"))
                .andExpect(jsonPath("$.securities[0].isin").value("US0000TRNC07"))
                .andExpect(jsonPath("$.securities[0].currentPrice").value(185.25));
        verify(broker).findSecurity("trnc");
    }

    @Test
    void searchReturnsCandidates() throws Exception {
        when(broker.findSecurity("Training Corp")).thenReturn(SearchResult.candidates(List.of(TRNC, TRNP)));

        mvc.perform(get("/securities/search").param("query", "Training Corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("CANDIDATES"))
                .andExpect(jsonPath("$.securities.length()").value(2));
    }

    @Test
    void searchReturnsNotFoundMatchWithEmptyListAndStatus200() throws Exception {
        when(broker.findSecurity("zzz")).thenReturn(SearchResult.notFound());

        mvc.perform(get("/securities/search").param("query", "zzz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("NOT_FOUND"))
                .andExpect(jsonPath("$.securities.length()").value(0));
    }

    @Test
    void searchWithoutQueryParamIsBadRequest() throws Exception {
        mvc.perform(get("/securities/search"))
                .andExpect(status().isBadRequest());
    }
}
