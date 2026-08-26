package io.realmledger.port;

import io.realmledger.core.leaderboard.ScoreEntry;
import java.util.List;
import java.util.OptionalInt;

/**
 * Persistence port for season leaderboards.
 *
 * <p>Modelled on a sorted-set backend: writes are "set this player's score", reads are
 * range queries. Deliberately narrow, so that swapping Redis for something else does not
 * leak into the application layer.
 */
public interface LeaderboardStore {

    /** Records a player's score for a season, replacing any previous value. */
    void submitScore(String seasonId, ScoreEntry entry);

    /** Highest scores first. {@code offset} is 0-based. */
    List<ScoreEntry> range(String seasonId, int offset, int limit);

    /** 1-based rank, or empty if the player has no score in this season. */
    OptionalInt rankOf(String seasonId, long playerId);
}
