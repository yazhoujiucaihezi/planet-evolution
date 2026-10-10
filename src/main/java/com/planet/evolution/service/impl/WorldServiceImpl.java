package com.planet.evolution.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.World;
import com.planet.evolution.service.SaveService;
import com.planet.evolution.service.WorldService;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.MajorEventRules;

@Service
public class WorldServiceImpl implements WorldService {

    private final World world;
    private final EvolutionEngine engine;
    private final WorldMapper worldMapper;
    private final SaveService saveService;

    public WorldServiceImpl(World world, EvolutionEngine engine, WorldMapper worldMapper,
            SaveService saveService) {
        this.world = world;
        this.engine = engine;
        this.worldMapper = worldMapper;
        this.saveService = saveService;
    }

    @Override
    public void advance(int years) {
        advance(years, years <= 100);
    }

    @Override
    public void advanceQuietly(int years) {
        // 自动推进每秒都在跑：一律关掉 AI，否则请求会把叙事队列塞爆
        advance(years, false);
    }

    /**
     * 实际推进：narrate 决定这段时间内是否允许发起 AI 叙事/演化请求。
     * 用 engine 作监视器，避免自动推进（后台线程）与手动推进/跳过时代/新游戏同时改世界。
     */
    private void advance(int years, boolean narrate) {
        synchronized (engine) {
            engine.setNarrateEnabled(narrate);
            for (int i = 0; i < years; i++) {
                engine.tick();
                for (GameEvent event : engine.drainEvents()) {
                    worldMapper.insertEvent(event);
                }
                saveEveryHundredYears();
            }
        }
    }

    /** 每推进 100 年自动存档一次（存档失败只记日志，不影响推进）。 */
    private void saveEveryHundredYears() {
        int year = world.getYear();
        if (year > 0 && year % 100 == 0) {
            saveService.save();
        }
    }

    @Override
    public MajorRunResult advanceToNextMajorEvent(int maxYears) {
        int advanced = 0;
        // 长推进：本次不写 AI 叙事
        synchronized (engine) {
            engine.setNarrateEnabled(false);
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
                saveEveryHundredYears();
                if (major != null) {
                    // 遇到重大事件立即结束本次推进
                    return new MajorRunResult(world.getYear(), advanced, true, false, major);
                }
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
