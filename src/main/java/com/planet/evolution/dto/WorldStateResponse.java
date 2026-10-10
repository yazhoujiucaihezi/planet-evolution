package com.planet.evolution.dto;

import java.util.List;

/** 世界状态响应。pillars 是玩家文明的「四柱」（参考《王权》），玩家已灭亡时为 null。 */
public record WorldStateResponse(WorldState world, int speed, boolean paused, Pillars pillars,
        List<CivilizationView> civilizations) {

    public record WorldState(int year, String planet, double temperature, double habitability,
            double food, double resources, long population, int techTotal,
            int aliveCivilizations, int extinctCivilizations, String era) {
    }

    /** 玩家文明的四柱，取值 0~100。 */
    public record Pillars(int science, int welfare, int resource, int military) {
    }

    public record CivilizationView(long id, String name, int foundedYear, long population, int techLevel) {
    }
}
