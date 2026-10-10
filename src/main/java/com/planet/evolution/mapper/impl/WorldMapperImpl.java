package com.planet.evolution.mapper.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Repository;

import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.simulation.GameEvent;

/** 内存实现：时间倍率、暂停标记、历史事件列表。 */
@Repository
public class WorldMapperImpl implements WorldMapper {

    private final AtomicInteger speed = new AtomicInteger(1);
    private final AtomicBoolean paused = new AtomicBoolean(true);
    private final List<GameEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void updateSpeed(int speed) {
        this.speed.set(speed);
    }

    @Override
    public void updatePaused(boolean paused) {
        this.paused.set(paused);
    }

    @Override
    public void insertEvent(GameEvent event) {
        events.add(event);
    }

    @Override
    public int selectSpeed() {
        return speed.get();
    }

    @Override
    public boolean selectPaused() {
        return paused.get();
    }

    @Override
    public List<GameEvent> selectEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    @Override
    public void replaceEvents(List<GameEvent> replacement) {
        synchronized (events) {
            events.clear();
            if (replacement != null) {
                events.addAll(replacement);
            }
        }
    }

    @Override
    public void clearEvents() {
        synchronized (events) {
            events.clear();
        }
    }
}
