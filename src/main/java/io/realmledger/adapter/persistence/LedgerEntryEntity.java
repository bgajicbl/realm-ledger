package io.realmledger.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * JPA mapping for one ledger row.
 *
 * <p>Separate from {@link io.realmledger.core.wallet.LedgerEntry} on purpose. The domain
 * record stays immutable with validating constructors; JPA needs a no-arg constructor and
 * mutable fields, and letting that requirement into the core would quietly destroy its
 * invariants.
 *
 * <p>Implements {@link Persistable} because the id is assigned by the application rather
 * than generated. Without it Spring Data decides "not new" from the non-null id and calls
 * {@code merge}, which issues a guaranteed-miss SELECT before every insert. The ledger is
 * append-only and never updates a row, so {@code isNew()} is unconditionally true.
 */
@Entity
@Table(name = "wallet_ledger_entry")
public class LedgerEntryEntity implements Persistable<String> {

    @Id
    @Column(name = "entry_id", length = 36, nullable = false, updatable = false)
    private String entryId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private long playerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 8, nullable = false, updatable = false)
    private EntryTypeColumn entryType;

    @Column(name = "amount_units", nullable = false, updatable = false)
    private long amountUnits;

    @Column(name = "idempotency_key", length = 64, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "reason", length = 64, nullable = false, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected LedgerEntryEntity() {
        // for JPA
    }

    LedgerEntryEntity(
            String entryId,
            long playerId,
            EntryTypeColumn entryType,
            long amountUnits,
            String idempotencyKey,
            String reason,
            Instant occurredAt) {
        this.entryId = entryId;
        this.playerId = playerId;
        this.entryType = entryType;
        this.amountUnits = amountUnits;
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    @Override
    public String getId() {
        return entryId;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    String getEntryId() {
        return entryId;
    }

    long getPlayerId() {
        return playerId;
    }

    EntryTypeColumn getEntryType() {
        return entryType;
    }

    long getAmountUnits() {
        return amountUnits;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getReason() {
        return reason;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    /** Mirrors the domain enum without coupling the schema to it. */
    public enum EntryTypeColumn {
        CREDIT,
        DEBIT
    }
}
