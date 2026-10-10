package com.planet.evolution.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.PlanetProfile;

/** 单例世界与演化引擎。换星球只需替换这里的 PlanetProfile 实现。 */
@Configuration
public class WorldConfig {

    /** 世界演化的随机种子，固定后结果可复现。 */
    private static final long SEED = 20261009L;

    @Bean
    public PlanetProfile planetProfile() {
        return new EarthLikeProfile();
    }

    @Bean
    public World world() {
        World world = new World(new Planet("盖亚", 15.0, 1.0));
        // 开局第一个文明固定是玩家（id=1）：点亮它的领土和一圈接壤区域，其余留在黑雾里
        Civilization player = world.spawnCivilization("人类");
        world.exploreAround(player);
        return world;
    }

    @Bean
    public EvolutionEngine evolutionEngine(World world, PlanetProfile planetProfile) {
        return new EvolutionEngine(world, planetProfile, SEED);
    }
}
