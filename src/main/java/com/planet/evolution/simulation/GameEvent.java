package com.planet.evolution.simulation;

/** 历史事件记录。{@code rarity} 为 0~100 的稀有度，>= 60 视为重大事件。 */
public record GameEvent(int year, EventType type, String description, int rarity) {

    /** 兼容旧调用：稀有度按类型估算。 */
    public GameEvent(int year, EventType type, String description) {
        this(year, type, description, MajorEventRules.estimateRarity(type));
    }
}
