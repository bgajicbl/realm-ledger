package io.realmledger.core.wallet;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The wallet rules, as a pure function of history.
 *
 * <p>No Spring, no repository, no clock, no logging. Everything this class needs is
 * passed in, which is why the interesting cases (replay, key reuse with a different
 * payload, exact-balance debit, overflow) are cheap to test and cheap to reason about.
 * Persistence and transport live in the adapters.
 */
public final class WalletLedger {

    private WalletLedger() {
    }

    /** Folds the append-only history into a current balance. */
    public static Amount balance(List<LedgerEntry> history) {
        Objects.requireNonNull(history, "history");
        long units = 0;
        for (LedgerEntry entry : history) {
            units = Math.addExact(units, entry.signedUnits());
        }
        if (units < 0) {
            throw new IllegalStateException(
                    "Ledger history folds to a negative balance (" + units + "); the append-only "
                            + "invariant has been violated upstream");
        }
        return new Amount(units);
    }

    /**
     * Decides what should happen for {@code command}, given everything already written
     * for that player. Writes nothing.
     *
     * @param history all entries for the command's player, in any order
     * @param command the requested debit
     * @param entryId identifier to assign if a new entry is created
     * @param at      the instant to stamp on a new entry
     */
    public static DebitDecision decide(
            List<LedgerEntry> history, DebitCommand command, String entryId, Instant at) {

        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(command, "command");

        Optional<LedgerEntry> previous = findByKey(history, command.idempotencyKey());
        if (previous.isPresent()) {
            LedgerEntry original = previous.get();
            return matches(original, command)
                    ? new DebitDecision.Replayed(original)
                    : new DebitDecision.KeyConflict(original, command);
        }

        if (command.amount().isZero()) {
            return new DebitDecision.Rejected(DebitDecision.Reason.ZERO_AMOUNT, balance(history));
        }

        Amount available = balance(history);
        if (command.amount().isGreaterThan(available)) {
            return new DebitDecision.Rejected(DebitDecision.Reason.INSUFFICIENT_FUNDS, available);
        }

        LedgerEntry entry = new LedgerEntry(
                entryId,
                command.playerId(),
                EntryType.DEBIT,
                command.amount(),
                command.idempotencyKey(),
                command.reason(),
                at);
        return new DebitDecision.Applied(entry);
    }

    private static Optional<LedgerEntry> findByKey(List<LedgerEntry> history, IdempotencyKey key) {
        return history.stream().filter(e -> e.idempotencyKey().equals(key)).findFirst();
    }

    /**
     * A retry is only a retry if it asks for the same thing. Amount and player must
     * match; {@code reason} is descriptive metadata and is deliberately not part of the
     * comparison, so a client that improves its logging text does not get a 409.
     */
    private static boolean matches(LedgerEntry original, DebitCommand command) {
        return original.playerId().equals(command.playerId())
                && original.amount().equals(command.amount())
                && original.type() == EntryType.DEBIT;
    }
}
