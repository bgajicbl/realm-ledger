package io.realmledger.core.wallet;

/**
 * Client-supplied key that makes a wallet mutation safe to retry.
 *
 * <p>Bounded in length because it is stored in a unique index; an unbounded key
 * would let a client blow up the index and, with it, write throughput.
 */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 64;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "IdempotencyKey must be at most " + MAX_LENGTH + " characters, got " + value.length());
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
