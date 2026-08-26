package io.realmledger.core.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.realmledger.spec.SpecRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The wallet rules, tested as a pure function.
 *
 * <p>No Spring context, no database, no clock. Every case here runs in microseconds,
 * which is what lets the agent loop stay tight.
 */
class WalletLedgerTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");
    private static final PlayerId PLAYER = PlayerId.of(7);

    @Nested
    @DisplayName("balance")
    class Balance {

        @Test
        @SpecRef("WD-8")
        @DisplayName("is folded from the append-only history, not stored")
        void foldsSignedEntries() {
            List<LedgerEntry> history = List.of(
                    credit("e1", 500), debit("e2", 120, "k2"), credit("e3", 40));

            assertThat(WalletLedger.balance(history)).isEqualTo(Amount.of(420));
        }

        @Test
        @SpecRef("WD-8")
        @DisplayName("fails loudly if the history folds negative")
        void refusesImpossibleHistory() {
            List<LedgerEntry> broken = List.of(debit("e1", 10, "k1"));

            assertThatThrownBy(() -> WalletLedger.balance(broken))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("append-only invariant");
        }
    }

    @Nested
    @DisplayName("decide")
    class Decide {

        @Test
        @SpecRef("WD-1")
        @DisplayName("applies a debit that fits within the balance")
        void appliesDebitWithinBalance() {
            DebitDecision decision = decide(List.of(credit("e1", 500)), 200, "k-1");

            assertThat(decision)
                    .isInstanceOfSatisfying(DebitDecision.Applied.class, applied -> {
                        assertThat(applied.entry().type()).isEqualTo(EntryType.DEBIT);
                        assertThat(applied.entry().amount()).isEqualTo(Amount.of(200));
                        assertThat(applied.entry().occurredAt()).isEqualTo(NOW);
                    });
        }

        @Test
        @SpecRef("WD-3")
        @DisplayName("applies a debit for exactly the whole balance")
        void appliesDebitOfExactBalance() {
            assertThat(decide(List.of(credit("e1", 500)), 500, "k-1"))
                    .isInstanceOf(DebitDecision.Applied.class);
        }

        @Test
        @SpecRef("WD-2")
        @DisplayName("rejects a debit one unit above the balance and reports what is available")
        void rejectsDebitAboveBalance() {
            DebitDecision decision = decide(List.of(credit("e1", 500)), 501, "k-1");

            assertThat(decision)
                    .isInstanceOfSatisfying(DebitDecision.Rejected.class, rejected -> {
                        assertThat(rejected.reason())
                                .isEqualTo(DebitDecision.Reason.INSUFFICIENT_FUNDS);
                        assertThat(rejected.available()).isEqualTo(Amount.of(500));
                    });
        }

        @Test
        @SpecRef("WD-7")
        @DisplayName("rejects a zero debit")
        void rejectsZeroAmount() {
            assertThat(decide(List.of(credit("e1", 500)), 0, "k-1"))
                    .isInstanceOfSatisfying(DebitDecision.Rejected.class, rejected ->
                            assertThat(rejected.reason())
                                    .isEqualTo(DebitDecision.Reason.ZERO_AMOUNT));
        }

        @Test
        @SpecRef("WD-4")
        @DisplayName("returns the original entry when the same key repeats the same request")
        void replaysIdenticalRequest() {
            LedgerEntry original = debit("e2", 120, "k-retry");

            assertThat(decide(List.of(credit("e1", 500), original), 120, "k-retry"))
                    .isInstanceOfSatisfying(DebitDecision.Replayed.class, replayed ->
                            assertThat(replayed.original().entryId()).isEqualTo("e2"));
        }

        @Test
        @SpecRef("WD-5")
        @DisplayName("refuses a key reused for a different amount instead of replaying it")
        void refusesKeyReuseWithDifferentAmount() {
            List<LedgerEntry> history = List.of(credit("e1", 500), debit("e2", 120, "k-retry"));

            assertThat(decide(history, 300, "k-retry"))
                    .isInstanceOf(DebitDecision.KeyConflict.class);
        }

        @Test
        @SpecRef("WD-4")
        @DisplayName("still replays when only the free-text reason differs")
        void reasonIsNotPartOfTheIdempotencyComparison() {
            LedgerEntry original = debit("e2", 120, "k-retry");
            DebitCommand renamed = new DebitCommand(
                    PLAYER, Amount.of(120), IdempotencyKey.of("k-retry"), "different-wording");

            assertThat(WalletLedger.decide(
                            List.of(credit("e1", 500), original), renamed, "new", NOW))
                    .isInstanceOf(DebitDecision.Replayed.class);
        }
    }

    private static DebitDecision decide(List<LedgerEntry> history, long amount, String key) {
        return WalletLedger.decide(
                history,
                new DebitCommand(PLAYER, Amount.of(amount), IdempotencyKey.of(key), "test"),
                "new-entry",
                NOW);
    }

    private static LedgerEntry credit(String id, long units) {
        return entry(id, EntryType.CREDIT, units, "seed-" + id);
    }

    private static LedgerEntry debit(String id, long units, String key) {
        return entry(id, EntryType.DEBIT, units, key);
    }

    private static LedgerEntry entry(String id, EntryType type, long units, String key) {
        return new LedgerEntry(
                id,
                PLAYER,
                type,
                Amount.of(units),
                IdempotencyKey.of(key),
                "test",
                NOW.minusSeconds(3600));
    }
}
