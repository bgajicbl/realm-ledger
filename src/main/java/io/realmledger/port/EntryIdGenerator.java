package io.realmledger.port;

/**
 * Supplies ledger entry identifiers.
 *
 * <p>A port rather than a call to {@code UUID.randomUUID()} inside the service, so that
 * tests can produce deterministic entry ids and assert on them.
 */
@FunctionalInterface
public interface EntryIdGenerator {
    String nextId();
}
