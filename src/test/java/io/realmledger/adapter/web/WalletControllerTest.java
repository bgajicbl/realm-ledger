package io.realmledger.adapter.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.realmledger.application.WalletService;
import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.DebitCommand;
import io.realmledger.core.wallet.DebitDecision;
import io.realmledger.core.wallet.EntryType;
import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.spec.SpecRef;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Status-code mapping only.
 *
 * <p>The rules themselves are covered by {@code WalletLedgerTest}; duplicating them here
 * through HTTP would make the suite slower without making it stronger. What this class
 * proves is that each {@code DebitDecision} case reaches the client as the agreed status.
 */
@WebMvcTest(controllers = WalletController.class)
class WalletControllerTest {

    private static final PlayerId PLAYER = PlayerId.of(7);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WalletService wallet;

    @Test
    @SpecRef("WD-1")
    @DisplayName("an applied debit answers 201")
    void appliedIsCreated() throws Exception {
        given(wallet.debit(any())).willReturn(new DebitDecision.Applied(entry("e1", 120)));
        given(wallet.balanceOf(any())).willReturn(Amount.of(380));

        mvc.perform(debitRequest("k-1", 120))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryId").value("e1"))
                .andExpect(jsonPath("$.balance").value(380));
    }

    @Test
    @SpecRef("WD-4")
    @DisplayName("a replay answers 200 and flags itself")
    void replayIsOkAndFlagged() throws Exception {
        given(wallet.debit(any())).willReturn(new DebitDecision.Replayed(entry("e1", 120)));
        given(wallet.balanceOf(any())).willReturn(Amount.of(380));

        mvc.perform(debitRequest("k-1", 120))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.entryId").value("e1"));
    }

    @Test
    @SpecRef("WD-5")
    @DisplayName("a reused key with a different payload answers 409")
    void keyConflictIsConflict() throws Exception {
        DebitCommand attempted = new DebitCommand(
                PLAYER, Amount.of(300), IdempotencyKey.of("k-1"), "buy-troops");
        given(wallet.debit(any()))
                .willReturn(new DebitDecision.KeyConflict(entry("e1", 120), attempted));

        mvc.perform(debitRequest("k-1", 300))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @SpecRef("WD-2")
    @DisplayName("insufficient funds answers 422 and says how much is available")
    void insufficientFundsIsUnprocessable() throws Exception {
        given(wallet.debit(any())).willReturn(new DebitDecision.Rejected(
                DebitDecision.Reason.INSUFFICIENT_FUNDS, Amount.of(50)));

        mvc.perform(debitRequest("k-1", 120))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.available").value(50));
    }

    @Test
    @SpecRef("WD-7")
    @DisplayName("a zero debit answers 400")
    void zeroAmountIsBadRequest() throws Exception {
        given(wallet.debit(any())).willReturn(new DebitDecision.Rejected(
                DebitDecision.Reason.ZERO_AMOUNT, Amount.of(500)));

        mvc.perform(debitRequest("k-1", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ZERO_AMOUNT"));
    }

    @Test
    @SpecRef("WD-9")
    @DisplayName("a missing Idempotency-Key header answers 400")
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mvc.perform(post("/v1/players/7/wallet/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":120,\"reason\":\"buy-troops\"}"))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            debitRequest(String key, long amount) {
        return post("/v1/players/7/wallet/debit")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"reason\":\"buy-troops\"}");
    }

    private static LedgerEntry entry(String id, long amount) {
        return new LedgerEntry(
                id,
                PLAYER,
                EntryType.DEBIT,
                Amount.of(amount),
                IdempotencyKey.of("k-1"),
                "buy-troops",
                Instant.parse("2026-08-26T10:00:00Z"));
    }
}
