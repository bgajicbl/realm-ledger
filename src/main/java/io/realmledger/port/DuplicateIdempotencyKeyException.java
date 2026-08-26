package io.realmledger.port;

import io.realmledger.core.wallet.IdempotencyKey;

/**
 * Raised when the unique index on the ledger rejects an append.
 *
 * <p>This is the expected outcome of two concurrent retries of the same request, not an
 * error condition: exactly one of them wins the insert and the loser reads back the
 * winner's entry. Treating it as a normal control-flow signal is why the wallet does not
 * need a distributed lock.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient IdempotencyKey key;

    public DuplicateIdempotencyKeyException(IdempotencyKey key, Throwable cause) {
        super("Idempotency key already present: " + key, cause);
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}
