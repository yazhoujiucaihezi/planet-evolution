package com.planet.evolution.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.planet.evolution.model.Player;

/**
 * 在线玩家管理：加入、心跳、查询。
 *
 * <p>玩家用浏览器会话 id 标识；心跳靠前端每秒 {@code /api/world/sync?sessionId=...} 刷新，
 * 超过 {@link #ONLINE_TIMEOUT_MILLIS} 没有心跳的视为离线（不删除记录，只是不再算在线）。
 */
@Service
public class PlayerService {

    /** 心跳超时：超过这么久没心跳就不算在线。 */
    private static final long ONLINE_TIMEOUT_MILLIS = 60_000L;

    /** sessionId -> 玩家。 */
    private final Map<String, Player> players = new ConcurrentHashMap<>();

    /** 加入游戏：分配一个新 sessionId 并登记。 */
    public Player join(String name) {
        String sessionId = UUID.randomUUID().toString();
        String safeName = (name == null || name.isBlank()) ? "匿名" : name.trim();
        Player player = new Player(sessionId, safeName);
        players.put(sessionId, player);
        return player;
    }

    /** 刷新心跳；session 不存在时静默忽略。 */
    public void touch(String sessionId) {
        Player player = players.get(sessionId);
        if (player != null) {
            player.touch();
        }
    }

    /** 按 sessionId 取玩家；不存在返回 null。 */
    public Player get(String sessionId) {
        return sessionId == null ? null : players.get(sessionId);
    }

    /**
     * 该文明是否已被「别的」在线玩家控制（join 分配文明时用来跳过）。
     *
     * @param civId            待检查的文明 id
     * @param exceptSessionId  排除掉这个 session（一般是刚加入、还没分配文明的玩家）
     */
    public boolean isControlledByOther(int civId, String exceptSessionId) {
        if (civId <= 0) {
            return false;
        }
        for (Player player : listOnline()) {
            if (player.getControlledCivId() == civId
                    && !player.getSessionId().equals(exceptSessionId)) {
                return true;
            }
        }
        return false;
    }

    /** 在线玩家当前控制的文明 id（去重，忽略 0 / 负数）。终止判定据此判断「还有没有玩家的文明活着」。 */
    public List<Integer> controlledCivIds() {
        List<Integer> ids = new ArrayList<>();
        for (Player player : listOnline()) {
            int civId = player.getControlledCivId();
            if (civId > 0 && !ids.contains(civId)) {
                ids.add(civId);
            }
        }
        return ids;
    }

    /** 当前在线玩家列表（按心跳超时过滤）。 */
    public List<Player> listOnline() {
        List<Player> online = new ArrayList<>();
        for (Player player : players.values()) {
            if (player.isOnline(ONLINE_TIMEOUT_MILLIS)) {
                online.add(player);
            }
        }
        return online;
    }

    /** 当前在线玩家数。 */
    public int onlineCount() {
        return listOnline().size();
    }
}
