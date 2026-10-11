package com.planet.evolution.dto;

import java.util.List;
import java.util.Map;

/**
 * 世界状态响应。pillars 是玩家文明的「四柱」（参考《王权》），玩家已灭亡时为 null。
 * gameOver / gameOverReason 是一局的终局状态；player 是玩家文明概览（不存在时为 null）。
 */
public record WorldStateResponse(WorldState world, int speed, boolean paused, Pillars pillars,
        PlayerView player, boolean gameOver, String gameOverReason,
        List<CivilizationView> civilizations) {

    public record WorldState(int year, String planet, double temperature, double habitability,
            double food, double resources, long population, int techTotal,
            int aliveCivilizations, int extinctCivilizations, String era) {
    }

    /** 玩家文明的四柱，取值 0~100。 */
    public record Pillars(int science, int welfare, int resource, int military) {
    }

    /**
     * 玩家文明概览：科技值、个人时代、五种资源点、四个状态指标。
     *
     * @param points     五种资源点，键为 food / production / military / culture / science
     * @param indicators 四个状态指标，键为 unrest / culture / prestige / tension
     */
    public record PlayerView(long id, String name, int foundedYear, long population, int techLevel,
            String era, int eraIndex, Map<String, Integer> points, Map<String, Integer> indicators) {
    }

    public record CivilizationView(long id, String name, int foundedYear, long population, int techLevel) {
    }
}
