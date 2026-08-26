package io.realmledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.DebitCommand;
import io.realmledger.core.wallet.DebitDecision;
import io.realmledger.core.wallet.EntryType;
import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.fake.InMemoryLedgerEntryStore;
import io.realmledger.spec.SpecRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The service layer, whose only real job is resolving the lost-insert race. */
class WalletServiceTest {

    private static final PlayerId PLAYER = PlayerId.of(7);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    @Test
    @SpecRef("WD-1")
    @DisplayName("writes exactly one entry for an accepted debit")
    void appendsAcceptedDebit() {
        InMemoryLedgerEntryStore store = new InMemoryLedgerEntryStore(credit(500));
        WalletService service = new WalletService(store, () -> "entry-1", CLOCK);

        DebitDecision decision = service.debit(command(120, "k-1"));

        assertThat(decision).isInstanceOf(DebitDecision.Applied.class);
        assertThat(store.size()).isEqualTo(2);
        assertThat(service.balanceOf(PLAYER)).isEqualTo(Amount.of(380));
    }

    @Test
    @SpecRef("WD-4")
    @DisplayName("a sequential retry writes nothing and returns the original entry")
    void sequentialRetryIsAReplay() {
        InMemoryLedgerEntryStore store = new InMemoryLedgerEntryStore(credit(500));
        WalletService service = new WalletService(store, () -> "entry-1", CLOCK);

        service.debit(command(120, "k-retry"));
        DebitDecision second = service.debit(command(120, "k-retry"));

        assertThat(second)
                .isInstanceOfSatisfying(DebitDecision.Replayed.class, replayed ->
                        assertThat(replayed.original().entryId()).isEqualTo("entry-1"));
        assertThat(store.size()).isEqualTo(2);
        assertThat(service.balanceOf(PLAYER)).isEqualTo(Amount.of(380));
    }

    @Test
    @SpecRef("WD-6")
    @DisplayName("32 concurrent retries of one request produce exactly one ledger entry")
    void concurrentRetriesProduceOneEntry() throws Exception {
        int threads = 32;
        InMemoryLedgerEntryStore store = new InMemoryLedgerEntryStore(credit(500));
        AtomicInteger ids = new AtomicInteger();
        WalletService service =
                new WalletService(store, () -> "entry-" + ids.incrementAndGet(), CLOCK);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReferenceArray<DebitDecision> outcomes = new AtomicReferenceArray<>(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                int slot = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        outcomes.set(slot, service.debit(command(120, "k-race")));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        long applied = countOf(outcomes, DebitDecision.Applied.class);
        long replayed = countOf(outcomes, DebitDecision.Replayed.class);

        assertThat(applied).as("exactly one request wins the insert").isEqualTo(1);
        assertThat(replayed).as("every other request replays").isEqualTo(threads - 1L);
        assertThat(store.size()).as("one credit plus one debit").isEqualTo(2);
        assertThat(service.balanceOf(PLAYER)).isEqualTo(Amount.of(380));
    }

    @Test
    @SpecRef("WD-2")
    @DisplayName("a rejected debit writes nothing")
    void rejectedDebitWritesNothing() {
        InMemoryLedgerEntryStore store = new InMemoryLedgerEntryStore(credit(100));
        WalletService service = new WalletService(store, () -> "entry-1", CLOCK);

        assertThat(service.debit(command(101, "k-1"))).isInstanceOf(DebitDecision.Rejected.class);
        assertThat(store.size()).isEqualTo(1);
    }

    private static long countOf(AtomicReferenceArray<DebitDecision> outcomes, Class<?> type) {
        return java.util.stream.IntStream.range(0, outcomes.length())
                .mapToObj(outcomes::get)
                .filter(type::isInstance)
                .count();
    }

    private static DebitCommand command(long amount, String key) {
        return new DebitCommand(PLAYER, Amount.of(amount), IdempotencyKey.of(key), "buy-troops");
    }

    private static LedgerEntry credit(long units) {
        return new LedgerEntry(
                "seed",
                PLAYER,
                EntryType.CREDIT,
                Amount.of(units),
                IdempotencyKey.of("seed-key"),
                "seed",
                Instant.parse("2026-08-26T09:00:00Z"));
    }
}
