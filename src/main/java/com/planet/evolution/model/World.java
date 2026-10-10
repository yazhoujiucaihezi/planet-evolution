package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 世界状态：年份、星球、文明列表。 */
public class World {

    /** 世界从第 0 年开始。 */
    private int year;
    private final Planet planet;
    private final List<Civilization> civilizations = new ArrayList<>();
    private long nextCivilizationId = 1;
    private final RegionMap regionMap = new RegionMap();
    private final Random regionRandom;

    public World(Planet planet) {
        this(planet, new Random());
    }

    /** 指定随机源，便于固定种子复现。 */
    public World(Planet planet, Random regionRandom) {
        this.planet = planet;
        this.regionRandom = regionRandom;
    }

    /** 诞生一个新文明，初始人口 1000（保留原公开签名），并随机分配一块无主区域。 */
    public Civilization spawnCivilization(String name) {
        return spawnCivilization(name, 1000L);
    }

    /** 诞生一个新文明并指定初始人口；人口来源由调用方负责（例如从源文明分裂）。 */
    public Civilization spawnCivilization(String name, long initialPopulation) {
        Civilization civilization = new Civilization(nextCivilizationId++, name, year, initialPopulation);
        civilizations.add(civilization);
        assignRegion(civilization);
        return civilization;
    }

    /** 从无主区域里随机分一块给该文明；没有无主区域时不分（文明暂时无领土）。 */
    private void assignRegion(Civilization civilization) {
        Region region = regionMap.randomUnowned(regionRandom);
        if (region == null) {
            return;
        }
        region.setOwnerCivId((int) civilization.getId());
        civilization.addRegion(region.getId());
    }

    /** 区域地图。 */
    public RegionMap getRegionMap() {
        return regionMap;
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

    /** 恢复存档用：直接设置年份。 */
    public void setYear(int year) {
        this.year = year;
    }

    /** 恢复存档用：清空文明列表与区域归属（不影响星球参数）。 */
    public void clearCivilizations() {
        civilizations.clear();
        for (Region region : regionMap.all()) {
            region.setOwnerCivId(Region.UNOWNED);
        }
    }

    /** 恢复存档用：按原样放回一个文明（保留 id、诞生年、人口、科技、领土）与区域归属。 */
    public void restoreCivilization(Civilization civilization) {
        civilizations.add(civilization);
        if (civilization.getId() >= nextCivilizationId) {
            nextCivilizationId = civilization.getId() + 1;
        }
        // 写回区域归属，保持与文明领土对称
        for (Integer regionId : civilization.getTerritory()) {
            Region region = regionMap.get(regionId);
            if (region != null) {
                region.setOwnerCivId((int) civilization.getId());
            }
        }
    }
}
