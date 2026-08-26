import io.realmledger.application.LeaderboardService;
import io.realmledger.application.WalletService;
import io.realmledger.core.leaderboard.LeaderboardPage;
import io.realmledger.core.leaderboard.RankedPlayer;
import io.realmledger.core.leaderboard.Ranker;
import io.realmledger.core.leaderboard.ScoreEntry;
import io.realmledger.core.wallet.Amount;
import io.realmledger.core.wallet.DebitCommand;
import io.realmledger.core.wallet.DebitDecision;
import io.realmledger.core.wallet.EntryType;
import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.core.wallet.WalletLedger;
import io.realmledger.port.DuplicateIdempotencyKeyException;
import io.realmledger.port.LedgerEntryStore;
import io.realmledger.port.LeaderboardStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Dependency-free smoke check over the domain core and application services.
 *
 * <p>This is NOT a replacement for the JUnit suite. It exists because the agent loop
 * needs feedback in under a second, and a full {@code mvn verify} with Testcontainers
 * does not provide that. Run it with {@code make core-check}. If this fails, there is no
 * point running anything else.
 */
public final class SelfCheck {

    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        balanceFoldsSignedEntries();
        debitWithinBalanceIsApplied();
        debitOfExactBalanceIsApplied();
        debitAboveBalanceIsRejectedWithAvailable();
        zeroAmountIsRejected();
        replayOfSameKeyAndPayloadReturnsOriginal();
        sameKeyDifferentAmountIsConflict();
        concurrentRetryLosesInsertAndReadsBackWinner();
        negativeHistoryIsRefused();
        amountOverflowIsLoud();
        rankerUsesCompetitionRanking();
        rankerOrdersTiesDeterministically();
        leaderboardPageOffsetsRanks();

        System.out.printf("%nran %d checks, %d failed%n", checks, failures.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
        System.out.println("core self-check OK");
    }

    // ------------------------------------------------------------------ wallet core

    private static void balanceFoldsSignedEntries() {
        List<LedgerEntry> history = List.of(
                credit("e1", 500, "seed"),
                debit("e2", 120, "buy-troops"),
                credit("e3", 40, "quest"));
        check("balance folds credits and debits",
                WalletLedger.balance(history).units() == 420L);
    }

    private static void debitWithinBalanceIsApplied() {
        List<LedgerEntry> history = List.of(credit("e1", 500, "seed"));
        DebitDecision d = decide(history, 200, "k-1");
        check("debit within balance is applied", d instanceof DebitDecision.Applied);
        if (d instanceof DebitDecision.Applied applied) {
            check("applied entry is a DEBIT", applied.entry().type() == EntryType.DEBIT);
            check("applied entry carries the amount", applied.entry().amount().units() == 200L);
        }
    }

    private static void debitOfExactBalanceIsApplied() {
        List<LedgerEntry> history = List.of(credit("e1", 500, "seed"));
        check("debit of the exact balance is allowed",
                decide(history, 500, "k-1") instanceof DebitDecision.Applied);
    }

    private static void debitAboveBalanceIsRejectedWithAvailable() {
        List<LedgerEntry> history = List.of(credit("e1", 500, "seed"));
        DebitDecision d = decide(history, 501, "k-1");
        check("debit above balance is rejected", d instanceof DebitDecision.Rejected);
        if (d instanceof DebitDecision.Rejected rejected) {
            check("rejection reason is INSUFFICIENT_FUNDS",
                    rejected.reason() == DebitDecision.Reason.INSUFFICIENT_FUNDS);
            check("rejection reports what was available",
                    rejected.available().units() == 500L);
        }
    }

    private static void zeroAmountIsRejected() {
        DebitDecision d = decide(List.of(credit("e1", 500, "seed")), 0, "k-1");
        check("zero-amount debit is rejected",
                d instanceof DebitDecision.Rejected r
                        && r.reason() == DebitDecision.Reason.ZERO_AMOUNT);
    }

    private static void replayOfSameKeyAndPayloadReturnsOriginal() {
        LedgerEntry original = debitWithKey("e2", 120, "k-retry");
        List<LedgerEntry> history = List.of(credit("e1", 500, "seed"), original);
        DebitDecision d = decide(history, 120, "k-retry");
        check("replay returns the original entry",
                d instanceof DebitDecision.Replayed r && r.original().entryId().equals("e2"));
    }

    private static void sameKeyDifferentAmountIsConflict() {
        List<LedgerEntry> history =
                List.of(credit("e1", 500, "seed"), debitWithKey("e2", 120, "k-retry"));
        DebitDecision d = decide(history, 300, "k-retry");
        check("reusing a key with a different amount is a conflict, not a replay",
                d instanceof DebitDecision.KeyConflict);
    }

    private static void negativeHistoryIsRefused() {
        List<LedgerEntry> broken = List.of(debit("e1", 10, "impossible"));
        boolean threw = false;
        try {
            WalletLedger.balance(broken);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check("a history that folds negative fails loudly", threw);
    }

    private static void amountOverflowIsLoud() {
        boolean threw = false;
        try {
            Amount.of(Long.MAX_VALUE).plus(Amount.of(1));
        } catch (ArithmeticException expected) {
            threw = true;
        }
        check("amount arithmetic overflow throws instead of wrapping", threw);
    }

    // --------------------------------------------------------- application: the race

    private static void concurrentRetryLosesInsertAndReadsBackWinner() {
        LedgerEntry winner = debitWithKey("winner", 120, "k-race");
        RacyStore store = new RacyStore(List.of(credit("e1", 500, "seed")), winner);
        WalletService service = new WalletService(store, () -> "loser", fixedClock());

        DebitDecision d = service.debit(new DebitCommand(
                PlayerId.of(7), Amount.of(120), IdempotencyKey.of("k-race"), "buy-troops"));

        check("loser of the unique-index race returns a replay",
                d instanceof DebitDecision.Replayed);
        if (d instanceof DebitDecision.Replayed r) {
            check("replay returns the winner's entry, not the loser's",
                    r.original().entryId().equals("winner"));
        }
        check("the loser wrote nothing", store.appendAttempts == 1 && store.stored.isEmpty());
    }

    // ------------------------------------------------------------- leaderboard core

    private static void rankerUsesCompetitionRanking() {
        List<RankedPlayer> ranked = Ranker.rank(List.of(
                new ScoreEntry(PlayerId.of(1), 900),
                new ScoreEntry(PlayerId.of(2), 700),
                new ScoreEntry(PlayerId.of(3), 700),
                new ScoreEntry(PlayerId.of(4), 100)));
        List<Integer> ranks = ranked.stream().map(RankedPlayer::rank).toList();
        check("ties share a rank and the next rank skips (1,2,2,4)",
                ranks.equals(List.of(1, 2, 2, 4)));
    }

    private static void rankerOrdersTiesDeterministically() {
        List<ScoreEntry> a = List.of(
                new ScoreEntry(PlayerId.of(9), 700), new ScoreEntry(PlayerId.of(2), 700));
        List<ScoreEntry> b = List.of(
                new ScoreEntry(PlayerId.of(2), 700), new ScoreEntry(PlayerId.of(9), 700));
        check("tie order does not depend on input order",
                Ranker.rank(a).equals(Ranker.rank(b)));
        check("ties break by player id ascending",
                Ranker.rank(a).get(0).playerId().value() == 2L);
    }

    private static void leaderboardPageOffsetsRanks() {
        InMemoryLeaderboard store = new InMemoryLeaderboard();
        for (int i = 1; i <= 10; i++) {
            store.submitScore("s-2026-autumn", new ScoreEntry(PlayerId.of(i), i * 100L));
        }
        LeaderboardPage page = new LeaderboardService(store).page("s-2026-autumn", 3, 2);
        check("page ranks are absolute, not page-relative",
                page.players().get(0).rank() == 4 && page.players().get(1).rank() == 5);
        check("page returns the requested window size", page.players().size() == 2);
    }

    // ------------------------------------------------------------------------ fakes

    /** A store whose append always loses the unique-index race. */
    private static final class RacyStore implements LedgerEntryStore {
        private final List<LedgerEntry> history;
        private final LedgerEntry winner;
        final List<LedgerEntry> stored = new ArrayList<>();
        int appendAttempts = 0;

        RacyStore(List<LedgerEntry> history, LedgerEntry winner) {
            this.history = history;
            this.winner = winner;
        }

        @Override
        public List<LedgerEntry> historyOf(PlayerId playerId) {
            return history;
        }

        @Override
        public Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key) {
            return key.equals(winner.idempotencyKey()) ? Optional.of(winner) : Optional.empty();
        }

        @Override
        public LedgerEntry append(LedgerEntry entry) {
            appendAttempts++;
            throw new DuplicateIdempotencyKeyException(entry.idempotencyKey(), null);
        }
    }

    private static final class InMemoryLeaderboard implements LeaderboardStore {
        private final Map<String, Map<Long, Long>> seasons = new LinkedHashMap<>();

        @Override
        public void submitScore(String seasonId, ScoreEntry entry) {
            seasons.computeIfAbsent(seasonId, s -> new LinkedHashMap<>())
                    .put(entry.playerId().value(), entry.score());
        }

        @Override
        public List<ScoreEntry> range(String seasonId, int offset, int limit) {
            return sorted(seasonId).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public OptionalInt rankOf(String seasonId, long playerId) {
            List<ScoreEntry> all = sorted(seasonId);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).playerId().value() == playerId) {
                    return OptionalInt.of(i + 1);
                }
            }
            return OptionalInt.empty();
        }

        private List<ScoreEntry> sorted(String seasonId) {
            return seasons.getOrDefault(seasonId, Map.of()).entrySet().stream()
                    .map(e -> new ScoreEntry(PlayerId.of(e.getKey()), e.getValue()))
                    .sorted(Comparator.comparingLong(ScoreEntry::score).reversed()
                            .thenComparingLong(e -> e.playerId().value()))
                    .toList();
        }
    }

    // ------------------------------------------------------------------- test utils

    private static DebitDecision decide(List<LedgerEntry> history, long amount, String key) {
        return WalletLedger.decide(
                history,
                new DebitCommand(PlayerId.of(7), Amount.of(amount), IdempotencyKey.of(key), "test"),
                "new-entry",
                Instant.parse("2026-08-26T10:00:00Z"));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);
    }

    private static LedgerEntry credit(String id, long units, String reason) {
        return entry(id, EntryType.CREDIT, units, "seed-" + id, reason);
    }

    private static LedgerEntry debit(String id, long units, String reason) {
        return entry(id, EntryType.DEBIT, units, "seed-" + id, reason);
    }

    private static LedgerEntry debitWithKey(String id, long units, String key) {
        return entry(id, EntryType.DEBIT, units, key, "buy-troops");
    }

    private static LedgerEntry entry(
            String id, EntryType type, long units, String key, String reason) {
        return new LedgerEntry(
                id,
                PlayerId.of(7),
                type,
                Amount.of(units),
                IdempotencyKey.of(key),
                reason,
                Instant.parse("2026-08-26T09:00:00Z"));
    }

    private static void check(String description, boolean condition) {
        checks++;
        System.out.printf("  %s %s%n", condition ? "ok  " : "FAIL", description);
        if (!condition) {
            failures.add(description);
        }
    }

    private SelfCheck() {
    }
}
