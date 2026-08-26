package io.realmledger.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.EntryType;
import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.port.DuplicateIdempotencyKeyException;
import io.realmledger.spec.SpecRef;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The persistence adapter against a real MySQL.
 *
 * <p>This test exists because the interesting behaviour is not in the mapping, it is in
 * the database: whether the unique index actually fires, whether the vendor exception
 * translates, whether the Flyway schema matches what Hibernate expects. None of that can
 * be established with a test double, and all of it fails at startup or under load rather
 * than in a unit test.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)} turns off the rollback-per-test that
 * {@code @DataJpaTest} applies by default. A concurrency test inside one shared
 * transaction would prove nothing, because both threads would be writing to the same
 * connection.
 *
 * <p>Tagged {@code docker} and excluded from {@code mvn test}. Run it with
 * {@code make test-docker}.
 */
@Tag("docker")
@DataJpaTest
@Testcontainers
@Import(JpaLedgerEntryStore.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
class JpaLedgerEntryStoreIT {

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers extension
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JpaLedgerEntryStore store;

    @Test
    @SpecRef("WD-8")
    @DisplayName("an appended entry reads back intact, microsecond precision included")
    void roundTrip() {
        PlayerId player = PlayerId.of(nextPlayerId());
        Instant at = Instant.parse("2026-08-26T10:00:00.123456Z");
        LedgerEntry entry = entry(player, 250, "round-trip-" + UUID.randomUUID(), at);

        store.append(entry);

        assertThat(store.historyOf(player)).singleElement().satisfies(read -> {
            assertThat(read.amount()).isEqualTo(Amount.of(250));
            assertThat(read.occurredAt().truncatedTo(ChronoUnit.MICROS))
                    .isEqualTo(at.truncatedTo(ChronoUnit.MICROS));
        });
    }

    @Test
    @SpecRef("WD-5")
    @DisplayName("the unique index surfaces as DuplicateIdempotencyKeyException, not a vendor type")
    void duplicateKeyIsTranslated() {
        PlayerId player = PlayerId.of(nextPlayerId());
        String key = "dupe-" + UUID.randomUUID();
        store.append(entry(player, 100, key, Instant.now()));

        assertThatThrownBy(() -> store.append(entry(player, 100, key, Instant.now())))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
    }

    @Test
    @SpecRef("WD-6")
    @DisplayName("16 concurrent appends of one key leave exactly one row in MySQL")
    void concurrentAppendsLeaveOneRow() throws Exception {
        int threads = 16;
        PlayerId player = PlayerId.of(nextPlayerId());
        String key = "race-" + UUID.randomUUID();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        store.append(entry(player, 100, key, Instant.now()));
                        accepted.incrementAndGet();
                    } catch (DuplicateIdempotencyKeyException expected) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(accepted.get()).as("exactly one writer wins").isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(store.historyOf(player)).hasSize(1);
    }

    private static final AtomicInteger PLAYER_IDS = new AtomicInteger(1000);

    private static long nextPlayerId() {
        return PLAYER_IDS.incrementAndGet();
    }

    private static LedgerEntry entry(PlayerId player, long units, String key, Instant at) {
        return new LedgerEntry(
                UUID.randomUUID().toString(),
                player,
                EntryType.CREDIT,
                Amount.of(units),
                IdempotencyKey.of(key),
                "test",
                at);
    }
}
