package io.realmledger.core.wallet;

/** Direction of a ledger entry. The ledger is append-only; nothing is ever updated. */
public enum EntryType {
    CREDIT,
    DEBIT
}
