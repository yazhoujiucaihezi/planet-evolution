package com.planet.evolution.service.impl;

import org.springframework.stereotype.Service;

import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.service.ClockService;

@Service
public class ClockServiceImpl implements ClockService {

    private final WorldMapper worldMapper;

    public ClockServiceImpl(WorldMapper worldMapper) {
        this.worldMapper = worldMapper;
    }

    @Override
    public void pause() {
        worldMapper.updatePaused(true);
    }

    @Override
    public void resume() {
        worldMapper.updatePaused(false);
    }

    @Override
    public int setSpeed(int speed) {
        worldMapper.updateSpeed(speed);
        return worldMapper.selectSpeed();
    }

    @Override
    public int currentSpeed() {
        return worldMapper.selectSpeed();
    }

    @Override
    public boolean isPaused() {
        return worldMapper.selectPaused();
    }
}
