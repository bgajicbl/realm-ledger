package io.realmledger.core.leaderboard;

import io.realmledger.core.wallet.PlayerId;

/** A player with a rank assigned. */
public record RankedPlayer(int rank, PlayerId playerId, long score) {

    public RankedPlayer {
        if (rank < 1) {
            throw new IllegalArgumentException("rank is 1-based, got " + rank);
        }
    }
}
