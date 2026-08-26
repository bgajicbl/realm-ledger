package io.realmledger.adapter.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Request body for a wallet debit. The idempotency key travels in a header, not here. */
public record DebitRequest(
        @Min(value = 0, message = "amount must not be negative") long amount,
        @Size(max = 64, message = "reason must be at most 64 characters") String reason) {
}
