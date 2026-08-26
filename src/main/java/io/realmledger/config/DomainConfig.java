package io.realmledger.config;

import io.realmledger.application.LeaderboardService;
import io.realmledger.application.WalletService;
import io.realmledger.port.EntryIdGenerator;
import io.realmledger.port.LeaderboardStore;
import io.realmledger.port.LedgerEntryStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit wiring for the framework-free application layer.
 *
 * <p>Keeping {@code @Component} out of {@code application} and {@code core} is the whole
 * point: those packages compile and test without Spring on the classpath, which is also
 * what makes the fast self-check in {@code tools/core-selfcheck} possible.
 */
@Configuration
public class DomainConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EntryIdGenerator entryIdGenerator() {
        return () -> UUID.randomUUID().toString();
    }

    @Bean
    public WalletService walletService(
            LedgerEntryStore store, EntryIdGenerator idGenerator, Clock clock) {
        return new WalletService(store, idGenerator, clock);
    }

    @Bean
    public LeaderboardService leaderboardService(LeaderboardStore store) {
        return new LeaderboardService(store);
    }
}
