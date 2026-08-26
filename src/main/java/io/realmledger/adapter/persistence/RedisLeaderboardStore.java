package io.realmledger.adapter.persistence;

import io.realmledger.core.leaderboard.ScoreEntry;
import io.realmledger.core.wallet.PlayerId;
import io.realmledger.port.LeaderboardStore;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

/**
 * Season leaderboards on Redis sorted sets.
 *
 * <p>One key per season, which keeps a finished season cheap to expire and stops a single
 * key growing without bound. {@code ZREVRANGE} gives the window and {@code ZREVRANK} gives
 * a single player's position without pulling the set into the application.
 *
 * <p>Known limit worth saying out loud: one key per season is one shard. Past roughly a
 * million active players in a season this needs bucketing (by region or by score band)
 * with a merge on read, and the merge is the part that is genuinely hard.
 */
@Repository
public class RedisLeaderboardStore implements LeaderboardStore {

    private static final String KEY_PREFIX = "leaderboard:season:";

    private final StringRedisTemplate redis;

    RedisLeaderboardStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void submitScore(String seasonId, ScoreEntry entry) {
        redis.opsForZSet()
                .add(key(seasonId), Long.toString(entry.playerId().value()), entry.score());
    }

    @Override
    public List<ScoreEntry> range(String seasonId, int offset, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .reverseRangeWithScores(key(seasonId), offset, (long) offset + limit - 1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .filter(t -> t.getValue() != null && t.getScore() != null)
                .map(t -> new ScoreEntry(
                        PlayerId.of(Long.parseLong(t.getValue())), t.getScore().longValue()))
                .toList();
    }

    @Override
    public OptionalInt rankOf(String seasonId, long playerId) {
        Long rank = redis.opsForZSet().reverseRank(key(seasonId), Long.toString(playerId));
        return rank == null ? OptionalInt.empty() : OptionalInt.of(rank.intValue() + 1);
    }

    private static String key(String seasonId) {
        return KEY_PREFIX + seasonId;
    }
}
