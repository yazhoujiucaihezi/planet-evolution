package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 世界状态：年份、星球、文明列表。 */
public class World {

    /** 世界从第 0 年开始。 */
    private int year;
    private final Planet planet;
    private final List<Civilization> civilizations = new ArrayList<>();
    private long nextCivilizationId = 1;

    public World(Planet planet) {
        this.planet = planet;
    }

    /** 诞生一个新文明，初始人口 1000（保留原公开签名）。 */
    public Civilization spawnCivilization(String name) {
        return spawnCivilization(name, 1000L);
    }

    /** 诞生一个新文明并指定初始人口；人口来源由调用方负责（例如从源文明分裂）。 */
    public Civilization spawnCivilization(String name, long initialPopulation) {
        Civilization civilization = new Civilization(nextCivilizationId++, name, year, initialPopulation);
        civilizations.add(civilization);
        return civilization;
    }

    /** 推进一年：只推进年份。人口、资源等演化由 EvolutionEngine 统一计算。 */
    public void tick() {
        year++;
    }

    public int getYear() {
        return year;
    }

    public Planet getPlanet() {
        return planet;
    }

    public List<Civilization> getCivilizations() {
        return Collections.unmodifiableList(civilizations);
    }

    /** 存活文明数量（population > 0）。只读统计，不改变列表内容。 */
    public int aliveCivilizationCount() {
        int count = 0;
        for (Civilization civilization : civilizations) {
            if (civilization.getPopulation() > 0) {
                count++;
            }
        }
        return count;
    }

    /** 灭亡文明数量（population <= 0）。只读统计，灭亡文明仍保留在列表中。 */
    public int extinctCivilizationCount() {
        return civilizations.size() - aliveCivilizationCount();
    }
}
