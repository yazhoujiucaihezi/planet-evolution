package com.planet.evolution.service;

import java.util.List;

import com.planet.evolution.simulation.GameEvent;

/** 世界查询与时间推进。 */
public interface WorldService {

    /** 推进指定年数。 */
    void advance(int years);

    /**
     * 自动推进用：与 {@link #advance(int)} 相同，但强制关闭 AI 叙事。
     * 世界时钟每秒都在推进，不能让每个周期都发起 AI 请求。
     */
    void advanceQuietly(int years);

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
