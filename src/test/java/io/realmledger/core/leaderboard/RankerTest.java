package io.realmledger.core.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.realmledger.core.wallet.PlayerId;
import io.realmledger.spec.SpecRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RankerTest {

    @Test
    @SpecRef("LB-1")
    @DisplayName("uses competition ranking so a shared rank skips the next one")
    void competitionRanking() {
        List<RankedPlayer> ranked = Ranker.rank(List.of(
                score(1, 900), score(2, 700), score(3, 700), score(4, 100)));

        assertThat(ranked).extracting(RankedPlayer::rank).containsExactly(1, 2, 2, 4);
    }

    @Test
    @SpecRef("LB-2")
    @DisplayName("orders ties by player id so pagination is stable")
    void tiesAreDeterministic() {
        List<RankedPlayer> one = Ranker.rank(List.of(score(9, 700), score(2, 700)));
        List<RankedPlayer> other = Ranker.rank(List.of(score(2, 700), score(9, 700)));

        assertThat(one).isEqualTo(other);
        assertThat(one.get(0).playerId()).isEqualTo(PlayerId.of(2));
    }

    @Test
    @DisplayName("returns an empty list for an empty season")
    void emptySeason() {
        assertThat(Ranker.rank(List.of())).isEmpty();
    }

    private static ScoreEntry score(long playerId, long value) {
        return new ScoreEntry(PlayerId.of(playerId), value);
    }
}
