package io.realmledger.adapter.persistence;

import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.EntryType;
import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.port.DuplicateIdempotencyKeyException;
import io.realmledger.port.LedgerEntryStore;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed ledger store.
 *
 * <p>Translates the vendor's unique-constraint violation into
 * {@link DuplicateIdempotencyKeyException} so the application layer can treat a lost
 * insert race as ordinary control flow. Everything else here is mapping.
 */
@Repository
public class JpaLedgerEntryStore implements LedgerEntryStore {

    private final LedgerEntryJpaRepository repository;

    JpaLedgerEntryStore(LedgerEntryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<LedgerEntry> historyOf(PlayerId playerId) {
        return repository.findByPlayerId(playerId.value()).stream()
                .map(JpaLedgerEntryStore::toDomain)
                .toList();
    }

    @Override
    public Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key) {
        return repository.findByIdempotencyKey(key.value()).map(JpaLedgerEntryStore::toDomain);
    }

    @Override
    public LedgerEntry append(LedgerEntry entry) {
        try {
            repository.saveAndFlush(toEntity(entry));
            return entry;
        } catch (DataIntegrityViolationException violation) {
            // The only unique constraint on this table is idempotency_key. If another is
            // ever added, this translation has to become more specific.
            throw new DuplicateIdempotencyKeyException(entry.idempotencyKey(), violation);
        }
    }

    private static LedgerEntry toDomain(LedgerEntryEntity entity) {
        return new LedgerEntry(
                entity.getEntryId(),
                PlayerId.of(entity.getPlayerId()),
                EntryType.valueOf(entity.getEntryType().name()),
                Amount.of(entity.getAmountUnits()),
                IdempotencyKey.of(entity.getIdempotencyKey()),
                entity.getReason(),
                entity.getOccurredAt());
    }

    private static LedgerEntryEntity toEntity(LedgerEntry entry) {
        return new LedgerEntryEntity(
                entry.entryId(),
                entry.playerId().value(),
                LedgerEntryEntity.EntryTypeColumn.valueOf(entry.type().name()),
                entry.amount().units(),
                entry.idempotencyKey().value(),
                entry.reason(),
                entry.occurredAt());
    }
}
