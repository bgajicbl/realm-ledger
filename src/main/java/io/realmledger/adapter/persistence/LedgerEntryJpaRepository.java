package io.realmledger.adapter.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByPlayerId(long playerId);

    Optional<LedgerEntryEntity> findByIdempotencyKey(String idempotencyKey);
}
