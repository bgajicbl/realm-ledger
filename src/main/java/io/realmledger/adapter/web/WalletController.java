package io.realmledger.adapter.web;

import io.realmledger.application.WalletService;
import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.DebitCommand;
import io.realmledger.core.wallet.DebitDecision;
import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for the wallet.
 *
 * <p><strong>The debit path deliberately has no {@code @Transactional}.</strong> Wrapping
 * it in one transaction breaks the very case it exists for: the losing side of the
 * unique-index race marks the shared transaction rollback-only, so the follow-up read runs
 * against a poisoned persistence context and the commit throws
 * {@code UnexpectedRollbackException}. The client gets a 500 for what is a successful
 * retry. There is nothing here that needs multi-statement atomicity, because the unique
 * index <em>is</em> the concurrency control; each repository call owning its own
 * transaction is both correct and what makes the read-back see the winner's committed row.
 *
 * <p>The switch over {@link DebitDecision} is
 * exhaustive because the interface is sealed: adding an outcome breaks this method at
 * compile time instead of silently returning the wrong status.
 */
@RestController
@RequestMapping("/v1/players/{playerId}/wallet")
public class WalletController {

    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final WalletService wallet;

    WalletController(WalletService wallet) {
        this.wallet = wallet;
    }

    @PostMapping("/debit")
    public ResponseEntity<?> debit(
            @PathVariable long playerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DebitRequest request) {

        DebitCommand command = new DebitCommand(
                PlayerId.of(playerId),
                Amount.of(request.amount()),
                IdempotencyKey.of(idempotencyKey),
                request.reason());

        return switch (wallet.debit(command)) {
            case DebitDecision.Applied applied -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(toResponse(applied.entry()));

            case DebitDecision.Replayed replayed -> ResponseEntity
                    .ok()
                    .header(REPLAY_HEADER, "true")
                    .body(toResponse(replayed.original()));

            case DebitDecision.KeyConflict conflict -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiError.of(
                            "IDEMPOTENCY_KEY_REUSED",
                            "Key " + conflict.original().idempotencyKey()
                                    + " was already used for a different debit"));

            case DebitDecision.Rejected rejected -> switch (rejected.reason()) {
                case INSUFFICIENT_FUNDS -> ResponseEntity
                        .unprocessableEntity()
                        .body(new ApiError(
                                "INSUFFICIENT_FUNDS",
                                "Balance is lower than the requested debit",
                                rejected.available().units()));
                case ZERO_AMOUNT -> ResponseEntity
                        .badRequest()
                        .body(ApiError.of("ZERO_AMOUNT", "A debit must be greater than zero"));
            };
        };
    }

    @GetMapping("/balance")
    @Transactional(readOnly = true)
    public BalanceResponse balance(@PathVariable long playerId) {
        return new BalanceResponse(playerId, wallet.balanceOf(PlayerId.of(playerId)).units());
    }

    private DebitResponse toResponse(LedgerEntry entry) {
        return new DebitResponse(
                entry.entryId(),
                entry.playerId().value(),
                entry.amount().units(),
                wallet.balanceOf(entry.playerId()).units(),
                entry.occurredAt());
    }

    /** Current balance for a player. */
    public record BalanceResponse(long playerId, long balance) {
    }
}
