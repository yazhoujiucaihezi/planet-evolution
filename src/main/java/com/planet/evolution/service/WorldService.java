package com.planet.evolution.service;

import java.util.List;

import com.planet.evolution.simulation.GameEvent;

/** 世界查询与时间推进。 */
public interface WorldService {

    /** 推进指定年数。 */
    void advance(int years);

    /**
     * 大事模式：持续推进直到出现稀有度 >= 60 的重大事件，或达到最大搜索年限。
     *
     * @param maxYears 最大搜索年限
     * @return 本次推进结果，包含是否遇到重大事件
     */
    MajorRunResult advanceToNextMajorEvent(int maxYears);

    List<GameEvent> listEvents();

    /** 大事模式推进结果。 */
    record MajorRunResult(int year, int advancedYears, boolean found, boolean truncated, GameEvent event) {
    }
}
