package io.realmledger.core.leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Assigns ranks to scores.
 *
 * <p>Uses standard competition ranking (1, 2, 2, 4) rather than dense ranking, because
 * players compare their rank against a published prize threshold: with dense ranking two
 * tied players at "rank 2" would both believe someone else holds rank 3.
 *
 * <p>Ties are broken for <em>ordering</em> by player id so that two calls with the same
 * data always return the same page. Without that, pagination silently duplicates or
 * skips players across page boundaries whenever scores collide, which they constantly do
 * at the bottom of a leaderboard.
 */
public final class Ranker {

    private Ranker() {
    }

    private static final Comparator<ScoreEntry> ORDER =
            Comparator.comparingLong(ScoreEntry::score).reversed()
                    .thenComparingLong(e -> e.playerId().value());

    public static List<RankedPlayer> rank(List<ScoreEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<ScoreEntry> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);

        List<RankedPlayer> ranked = new ArrayList<>(sorted.size());
        int rank = 0;
        long previousScore = Long.MIN_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            ScoreEntry entry = sorted.get(i);
            if (entry.score() != previousScore) {
                rank = i + 1;
                previousScore = entry.score();
            }
            ranked.add(new RankedPlayer(rank, entry.playerId(), entry.score()));
        }
        return List.copyOf(ranked);
    }
}
