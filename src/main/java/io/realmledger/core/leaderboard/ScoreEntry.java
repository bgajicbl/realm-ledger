package io.realmledger.core.leaderboard;

import io.realmledger.core.wallet.PlayerId;
import java.util.Objects;

/** A player's score within one season, before ranking is applied. */
public record ScoreEntry(PlayerId playerId, long score) {

    public ScoreEntry {
        Objects.requireNonNull(playerId, "playerId");
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative, got " + score);
        }
    }
}
