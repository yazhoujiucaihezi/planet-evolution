package com.planet.evolution.model;

/**
 * 一个在线玩家：浏览器会话 id + 昵称 + 当前控制的文明。
 * 由 {@link com.planet.evolution.service.PlayerService} 创建与维护。
 */
public class Player {

    private final String sessionId;
    private final String name;
    /** 当前控制的文明 id；未选择时为 0。 */
    private int controlledCivId;
    /** 最近一次心跳时间（毫秒），用于判定是否在线。 */
    private volatile long lastSeenMillis;

    public Player(String sessionId, String name) {
        this.sessionId = sessionId;
        this.name = name;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getName() {
        return name;
    }

    public int getControlledCivId() {
        return controlledCivId;
    }

    public void setControlledCivId(int controlledCivId) {
        this.controlledCivId = Math.max(0, controlledCivId);
    }

    /** 刷新心跳。 */
    public void touch() {
        this.lastSeenMillis = System.currentTimeMillis();
    }

    /** 距上次心跳是否在给定超时内（在线判定）。 */
    public boolean isOnline(long timeoutMillis) {
        return System.currentTimeMillis() - lastSeenMillis <= timeoutMillis;
    }
}
