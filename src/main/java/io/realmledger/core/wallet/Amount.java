package io.realmledger.core.wallet;

/**
 * A quantity of soft currency, in whole units. Never negative.
 *
 * <p>Deliberately a value object rather than a bare {@code long}: it makes the
 * "no negative amounts anywhere" invariant impossible to forget at a call site,
 * and it makes arithmetic overflow a loud failure instead of a wrapped balance.
 */
public record Amount(long units) implements Comparable<Amount> {

    public static final Amount ZERO = new Amount(0);

    public Amount {
        if (units < 0) {
            throw new IllegalArgumentException("Amount must not be negative, got " + units);
        }
    }

    public static Amount of(long units) {
        return new Amount(units);
    }

    public Amount plus(Amount other) {
        return new Amount(Math.addExact(units, other.units));
    }

    /** @throws IllegalArgumentException if the result would be negative. */
    public Amount minus(Amount other) {
        return new Amount(Math.subtractExact(units, other.units));
    }

    public boolean isZero() {
        return units == 0;
    }

    public boolean isGreaterThan(Amount other) {
        return units > other.units;
    }

    @Override
    public int compareTo(Amount other) {
        return Long.compare(units, other.units);
    }

    @Override
    public String toString() {
        return Long.toString(units);
    }
}
