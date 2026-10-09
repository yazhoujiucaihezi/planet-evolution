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
    AI_NARRATIVE("AI叙事");

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
