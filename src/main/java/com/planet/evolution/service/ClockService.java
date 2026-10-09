package com.planet.evolution.service;

/** 时间控制：暂停、恢复、倍率。 */
public interface ClockService {

    void pause();

    void resume();

    int setSpeed(int speed);

    int currentSpeed();

    boolean isPaused();
}
