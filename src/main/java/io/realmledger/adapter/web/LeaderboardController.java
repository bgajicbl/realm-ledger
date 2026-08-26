package io.realmledger.adapter.web;

import io.realmledger.application.LeaderboardService;
import io.realmledger.core.leaderboard.LeaderboardPage;
import io.realmledger.core.leaderboard.ScoreEntry;
import io.realmledger.core.wallet.PlayerId;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP surface for season leaderboards. */
@RestController
@RequestMapping("/v1/seasons/{seasonId}/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboard;

    LeaderboardController(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping
    public LeaderboardPage page(
            @PathVariable String seasonId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "20") @Min(1) int limit) {
        return leaderboard.page(seasonId, offset, limit);
    }

    @PutMapping("/players/{playerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(
            @PathVariable String seasonId,
            @PathVariable long playerId,
            @RequestBody ScoreSubmission submission) {
        leaderboard.submit(seasonId, new ScoreEntry(PlayerId.of(playerId), submission.score()));
    }

    @GetMapping("/players/{playerId}/rank")
    public RankResponse rank(@PathVariable String seasonId, @PathVariable long playerId) {
        int rank = leaderboard.rankOf(seasonId, playerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Player has no score in season " + seasonId));
        return new RankResponse(playerId, rank);
    }

    public record ScoreSubmission(long score) {
    }

    public record RankResponse(long playerId, int rank) {
    }
}
