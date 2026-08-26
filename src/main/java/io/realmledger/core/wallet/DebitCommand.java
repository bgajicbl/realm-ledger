package io.realmledger.core.wallet;

import java.util.Objects;

/** A request to remove currency from a player's wallet. */
public record DebitCommand(
        PlayerId playerId,
        Amount amount,
        IdempotencyKey idempotencyKey,
        String reason) {

    public DebitCommand {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        reason = (reason == null || reason.isBlank()) ? "unspecified" : reason;
    }
}
