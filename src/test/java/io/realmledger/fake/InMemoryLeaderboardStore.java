package io.realmledger.fake;

import io.realmledger.core.leaderboard.ScoreEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.port.LeaderboardStore;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Sorted-set semantics without Redis: last score wins, reads are descending by score. */
public final class InMemoryLeaderboardStore implements LeaderboardStore {

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
