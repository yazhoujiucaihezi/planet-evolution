package com.planet.evolution.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.planet.evolution.simulation.EvolutionEngine;

/**
 * 投票：多人时先发起、过半同意才执行；单人（在线 &le; 1）时直接执行、不发起投票。
 *
 * <p>支持的投票类型：
 * <ul>
 *   <li>{@code skip_era}：{@code targetValue} 是目标时代下标，转交 {@link EvolutionEngine#skipEra(int)}；</li>
 *   <li>{@code speed}：{@code targetValue} 是目标倍率，转交 {@link ClockService#setSpeed(int)}。</li>
 * </ul>
 *
 * <p>执行统一在本服务内完成：单人 propose 直接执行；多人 agree 达票时执行。
 * {@link com.planet.evolution.api.WorldController} 侧的 executeVote 属冗余兜底（此时投票已清空，不会重复生效）。
 */
@Service
public class VoteService {

    /** 一次投票的有效时长（秒）。 */
    private static final long VOTE_TIMEOUT_SECONDS = 30L;

    private final EvolutionEngine engine;
    private final ClockService clockService;
    private final PlayerService playerService;

    /** 当前进行中的投票；没有时为 null。 */
    private Vote currentVote;

    public VoteService(EvolutionEngine engine, ClockService clockService, PlayerService playerService) {
        this.engine = engine;
        this.clockService = clockService;
        this.playerService = playerService;
    }

    /** 提议结果：EXECUTED 已执行 / FAILED 执行失败 / BUSY 已有投票 / PROPOSED 已发起待投票。 */
    public enum VoteOutcome {
        EXECUTED, FAILED, BUSY, PROPOSED
    }

    /** 一次提议的处理结果。 */
    public record ProposeResult(VoteOutcome outcome, String message) {
    }

    /**
     * 一次投票。{@code agreed} 用并发集合，允许多个玩家并发同意；
     * {@code neededVotes} 是发起时按在线人数算出的过半票数。
     */
    public record Vote(String type, int targetValue, String proposerId, int neededVotes,
            Set<String> agreed, long deadlineMillis) {
    }

    /** 对外展示的投票状态。 */
    public record VoteStatus(String type, String proposerId, int currentVotes, int neededVotes,
            long remainingSeconds, int targetValue) {
    }

    /** 发起投票。已有进行中的投票返回 BUSY；单人直接执行。 */
    public synchronized ProposeResult propose(String type, int targetValue, String sessionId) {
        if (hasVote()) {
            return new ProposeResult(VoteOutcome.BUSY, "已有投票进行中");
        }
        int online = playerService.onlineCount();
        if (online <= 1) {
            // 单人：没有投票，直接执行
            return execute(type, targetValue);
        }
        int needed = online / 2 + 1;
        Set<String> agreed = ConcurrentHashMap.newKeySet();
        agreed.add(sessionId);
        long deadline = System.currentTimeMillis() + VOTE_TIMEOUT_SECONDS * 1000L;
        currentVote = new Vote(type, targetValue, sessionId, needed, agreed, deadline);
        return new ProposeResult(VoteOutcome.PROPOSED, "投票已发起，需要 " + needed + " 票");
    }

    /** 同意投票；达到票数则执行并返回 true，否则返回 false。 */
    public synchronized boolean agree(String sessionId) {
        if (!hasVote()) {
            return false;
        }
        currentVote.agreed().add(sessionId);
        if (currentVote.agreed().size() >= currentVote.neededVotes()) {
            Vote passed = currentVote;
            currentVote = null;
            execute(passed.type(), passed.targetValue());
            return true;
        }
        return false;
    }

    /** 是否已有进行中的投票（顺带清理过期投票）。 */
    public synchronized boolean hasVote() {
        if (currentVote == null) {
            return false;
        }
        if (System.currentTimeMillis() > currentVote.deadlineMillis()) {
            currentVote = null;
            return false;
        }
        return true;
    }

    /** 当前投票状态；没有进行中的投票时返回 null（前端据此隐藏投票条）。 */
    public synchronized VoteStatus status() {
        if (!hasVote()) {
            return null;
        }
        Vote vote = currentVote;
        long remaining = Math.max(0L, (vote.deadlineMillis() - System.currentTimeMillis()) / 1000L);
        return new VoteStatus(vote.type(), vote.proposerId(), vote.agreed().size(),
                vote.neededVotes(), remaining, vote.targetValue());
    }

    /** 当前投票（可能为 null）。 */
    public synchronized Vote getCurrentVote() {
        return currentVote;
    }

    /** 真正执行一次投票动作。 */
    private ProposeResult execute(String type, int targetValue) {
        if ("skip_era".equals(type)) {
            int before = engine.currentEraIndex();
            String message = engine.skipEra(targetValue);
            return engine.currentEraIndex() > before
                    ? new ProposeResult(VoteOutcome.EXECUTED, message)
                    : new ProposeResult(VoteOutcome.FAILED, message);
        }
        if ("speed".equals(type)) {
            int applied = clockService.setSpeed(targetValue);
            return new ProposeResult(VoteOutcome.EXECUTED, "速度已设为 " + applied);
        }
        return new ProposeResult(VoteOutcome.FAILED, "未知的投票类型：" + type);
    }
}
