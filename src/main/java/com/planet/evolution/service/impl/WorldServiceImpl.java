package com.planet.evolution.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.World;
import com.planet.evolution.service.WorldService;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.MajorEventRules;

@Service
public class WorldServiceImpl implements WorldService {

    private final World world;
    private final EvolutionEngine engine;
    private final WorldMapper worldMapper;

    public WorldServiceImpl(World world, EvolutionEngine engine, WorldMapper worldMapper) {
        this.world = world;
        this.engine = engine;
        this.worldMapper = worldMapper;
    }

    @Override
    public void advance(int years) {
        for (int i = 0; i < years; i++) {
            engine.tick();
            for (GameEvent event : engine.drainEvents()) {
                worldMapper.insertEvent(event);
            }
        }
    }

    @Override
    public MajorRunResult advanceToNextMajorEvent(int maxYears) {
        int advanced = 0;
        while (advanced < maxYears) {
            engine.tick();
            advanced++;
            GameEvent major = null;
            for (GameEvent event : engine.drainEvents()) {
                worldMapper.insertEvent(event);
                if (major == null && MajorEventRules.isMajor(event)) {
                    major = event;
                }
            }
            if (major != null) {
                // 遇到重大事件立即结束本次推进
                return new MajorRunResult(world.getYear(), advanced, true, false, major);
            }
        }
        // 达到最大搜索年限仍未出现重大事件
        return new MajorRunResult(world.getYear(), advanced, false, true, null);
    }

    @Override
    public List<GameEvent> listEvents() {
        return worldMapper.selectEvents();
    }
}
