package com.planet.evolution.simulation;

import java.util.List;

/** 事件类型。后续可扩展革命、帝国崛起等。 */
public enum EventType {
    CIVILIZATION_BIRTH("文明诞生"),
    DISASTER("灾害"),
    WAR("战争"),
    TECH_BREAKTHROUGH("科技突破"),
    EXTINCTION("文明灭亡"),
    /** AI 生成的历史叙事（每百年一条，稀有度固定 30，不触发大事模式）。 */
    AI_NARRATIVE("AI叙事"),
    /** AI 每 20 年驱动世界演化时生成的世界事件。 */
    AI_EVENT("世界事件"),
    /** 玩家用自然语言下达的指令。 */
    PLAYER_COMMAND("玩家指令");

    private final String label;

    EventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 事件对应的人文历史事件。 */
    public static List<String> disasterNames() {
        return List.of("大旱", "洪水", "瘟疫", "地震", "严冬");
    }
}
