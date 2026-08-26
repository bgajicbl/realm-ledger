package io.realmledger.application;

import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.DebitCommand;
import io.realmledger.core.wallet.DebitDecision;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.core.wallet.WalletLedger;
import io.realmledger.port.DuplicateIdempotencyKeyException;
import io.realmledger.port.EntryIdGenerator;
import io.realmledger.port.LedgerEntryStore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Applies wallet decisions and resolves the one race the pure core cannot see.
 *
 * <p>Two concurrent retries of the same request will both read a history without the key
 * and both decide {@code Applied}. The unique index breaks the tie: the loser catches
 * {@link DuplicateIdempotencyKeyException}, re-reads the winner's entry, and returns it as
 * a replay. No distributed lock, no SELECT FOR UPDATE, and correct under any number of
 * concurrent retries.
 *
 * <p>Deliberately free of framework annotations. It is wired by an explicit configuration
 * class, which keeps the whole decision path testable with plain constructors.
 */
public class WalletService {

    private final LedgerEntryStore store;
    private final EntryIdGenerator idGenerator;
    private final Clock clock;

    public WalletService(LedgerEntryStore store, EntryIdGenerator idGenerator, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Debits a player's wallet.
     *
     * <p>Must run inside a transaction in the adapter layer; this class does not open one,
     * so that the transaction boundary stays a deployment concern rather than a domain one.
     */
    public DebitDecision debit(DebitCommand command) {
        Objects.requireNonNull(command, "command");

        List<LedgerEntry> history = store.historyOf(command.playerId());
        DebitDecision decision =
                WalletLedger.decide(history, command, idGenerator.nextId(), clock.instant());

        if (!(decision instanceof DebitDecision.Applied applied)) {
            return decision;
        }

        try {
            store.append(applied.entry());
            return applied;
        } catch (DuplicateIdempotencyKeyException race) {
            // Another request with the same key won the insert between our read and our
            // write. Re-read and answer with the winner's entry.
            return store.findByIdempotencyKey(command.idempotencyKey())
                    .map(winner -> resolveRace(winner, command))
                    .orElseThrow(() -> new IllegalStateException(
                            "Unique index rejected key " + command.idempotencyKey()
                                    + " but no entry with that key can be read back", race));
        }
    }

    private DebitDecision resolveRace(LedgerEntry winner, DebitCommand command) {
        boolean sameRequest = winner.playerId().equals(command.playerId())
                && winner.amount().equals(command.amount())
                && winner.isDebit();
        return sameRequest
                ? new DebitDecision.Replayed(winner)
                : new DebitDecision.KeyConflict(winner, command);
    }

    /** Current balance, folded from history. */
    public Amount balanceOf(PlayerId playerId) {
        return WalletLedger.balance(store.historyOf(playerId));
    }
}
