package io.realmledger.application;

import io.realmledger.core.leaderboard.LeaderboardPage;
import io.realmledger.core.leaderboard.RankedPlayer;
import io.realmledger.core.leaderboard.Ranker;
import io.realmledger.core.leaderboard.ScoreEntry;
import io.realmledger.port.LeaderboardStore;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Reads and writes season leaderboards. */
public class LeaderboardService {

    public static final int MAX_PAGE_SIZE = 100;

    private final LeaderboardStore store;

    public LeaderboardService(LeaderboardStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void submit(String seasonId, ScoreEntry entry) {
        store.submitScore(requireSeason(seasonId), entry);
    }

    /**
     * Returns one page of a season leaderboard.
     *
     * <p>Ranks come from {@link Ranker} applied to the returned window rather than from
     * the store, so the tie rule is defined in exactly one place and does not depend on
     * the backend's tie behaviour.
     */
    public LeaderboardPage page(String seasonId, int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_PAGE_SIZE + ", got " + limit);
        }
        List<ScoreEntry> window = store.range(requireSeason(seasonId), offset, limit);
        List<RankedPlayer> ranked = Ranker.rank(window);
        List<RankedPlayer> offsetAdjusted = ranked.stream()
                .map(p -> new RankedPlayer(p.rank() + offset, p.playerId(), p.score()))
                .toList();
        return new LeaderboardPage(seasonId, offset, limit, offsetAdjusted);
    }

    public OptionalInt rankOf(String seasonId, long playerId) {
        return store.rankOf(requireSeason(seasonId), playerId);
    }

    private static String requireSeason(String seasonId) {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId must not be blank");
        }
        return seasonId;
    }
}
