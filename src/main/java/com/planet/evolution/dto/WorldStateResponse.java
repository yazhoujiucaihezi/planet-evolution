package com.planet.evolution.dto;

import java.util.List;

/** 世界状态响应。 */
public record WorldStateResponse(WorldState world, int speed, boolean paused, List<CivilizationView> civilizations) {

    public record WorldState(int year, String planet, double temperature, double habitability,
            double food, double resources, long population, int techTotal,
            int aliveCivilizations, int extinctCivilizations, String era) {
    }

    public record CivilizationView(long id, String name, int foundedYear, long population, int techLevel) {
    }
}
