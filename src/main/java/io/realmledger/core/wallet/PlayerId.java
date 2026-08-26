package io.realmledger.core.wallet;

/** Identifier of a player account. */
public record PlayerId(long value) {

    public PlayerId {
        if (value <= 0) {
            throw new IllegalArgumentException("PlayerId must be positive, got " + value);
        }
    }

    public static PlayerId of(long value) {
        return new PlayerId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
