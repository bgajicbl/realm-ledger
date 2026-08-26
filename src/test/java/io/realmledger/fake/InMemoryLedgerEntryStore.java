package io.realmledger.fake;

import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.port.DuplicateIdempotencyKeyException;
import io.realmledger.port.LedgerEntryStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the ledger.
 *
 * <p>Reproduces the one behaviour the application layer actually depends on: the append
 * is atomic on the idempotency key and a loser gets
 * {@link DuplicateIdempotencyKeyException}. {@code putIfAbsent} on a concurrent map is
 * the same guarantee a unique index gives, which is what makes the concurrency test in
 * {@code WalletServiceTest} meaningful rather than theatre.
 */
public final class InMemoryLedgerEntryStore implements LedgerEntryStore {

    private final List<LedgerEntry> entries = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, LedgerEntry> byKey = new ConcurrentHashMap<>();

    public InMemoryLedgerEntryStore(LedgerEntry... seed) {
        for (LedgerEntry entry : seed) {
            append(entry);
        }
    }

    @Override
    public List<LedgerEntry> historyOf(PlayerId playerId) {
        return entries.stream().filter(e -> e.playerId().equals(playerId)).toList();
    }

    @Override
    public Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key) {
        return Optional.ofNullable(byKey.get(key.value()));
    }

    @Override
    public LedgerEntry append(LedgerEntry entry) {
        LedgerEntry existing = byKey.putIfAbsent(entry.idempotencyKey().value(), entry);
        if (existing != null) {
            throw new DuplicateIdempotencyKeyException(entry.idempotencyKey(), null);
        }
        entries.add(entry);
        return entry;
    }

    public int size() {
        return entries.size();
    }

    public List<LedgerEntry> all() {
        return List.copyOf(entries);
    }
}
