package io.realmledger.core.leaderboard;

import java.util.List;
import java.util.Objects;

/** A contiguous slice of a season leaderboard. */
public record LeaderboardPage(String seasonId, int offset, int limit, List<RankedPlayer> players) {

    public LeaderboardPage {
        Objects.requireNonNull(seasonId, "seasonId");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
    }
}
