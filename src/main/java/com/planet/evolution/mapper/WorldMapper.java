package com.planet.evolution.mapper;

import java.util.List;

import com.planet.evolution.simulation.GameEvent;

/** 世界状态存储。当前为内存实现，以后换数据库只改这个接口的实现。 */
public interface WorldMapper {

    /** 设置时间倍率，1~100。 */
    void updateSpeed(int speed);

    /** 设置暂停/恢复。 */
    void updatePaused(boolean paused);

    /** 保存一条历史事件。 */
    void insertEvent(GameEvent event);

    int selectSpeed();

    boolean selectPaused();

    List<GameEvent> selectEvents();
}
