package io.realmledger.core.wallet;

/**
 * The outcome of evaluating a {@link DebitCommand} against a player's history.
 *
 * <p>Sealed so that every caller has to handle all four cases, and so that adding a
 * fifth outcome later breaks compilation at every switch instead of silently falling
 * into a default branch. The adapter layer maps each case onto an HTTP status; the
 * core stays free of any transport vocabulary.
 */
public sealed interface DebitDecision {

    /** The debit is new and valid. The entry has not been persisted yet. */
    record Applied(LedgerEntry entry) implements DebitDecision {}

    /**
     * This idempotency key was already used for an identical command. The original
     * entry is returned unchanged and nothing new is written.
     */
    record Replayed(LedgerEntry original) implements DebitDecision {}

    /**
     * This idempotency key was already used for a <em>different</em> command. That is a
     * client bug, not a retry, and answering with the original result would hide it.
     */
    record KeyConflict(LedgerEntry original, DebitCommand attempted) implements DebitDecision {}

    /** The debit is not allowed. */
    record Rejected(Reason reason, Amount available) implements DebitDecision {}

    enum Reason {
        INSUFFICIENT_FUNDS,
        ZERO_AMOUNT
    }
}
