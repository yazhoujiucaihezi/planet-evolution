package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个文明（含部落、国家等阶段）。 */
public class Civilization {

    private final long id;
    private String name;
    private int foundedYear;
    private long population;
    private int techLevel;
    /** 该文明占有的区域 id 列表。 */
    private final List<Integer> territory = new ArrayList<>();

    public Civilization(long id, String name, int foundedYear, long population) {
        this.id = id;
        this.name = name;
        this.foundedYear = foundedYear;
        this.population = population;
        this.techLevel = 1;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getFoundedYear() {
        return foundedYear;
    }

    public void setFoundedYear(int foundedYear) {
        this.foundedYear = foundedYear;
    }

    public long getPopulation() {
        return population;
    }

    public void setPopulation(long population) {
        this.population = population;
    }

    public int getTechLevel() {
        return techLevel;
    }

    public void setTechLevel(int techLevel) {
        this.techLevel = techLevel;
    }

    /** 该文明占有的区域 id（只读）。 */
    public List<Integer> getTerritory() {
        return Collections.unmodifiableList(territory);
    }

    /** 占有一个区域。 */
    public void addRegion(int regionId) {
        if (!territory.contains(regionId)) {
            territory.add(regionId);
        }
    }

    /** 失去一个区域。 */
    public void removeRegion(int regionId) {
        territory.remove(Integer.valueOf(regionId));
    }

    /** 恢复存档用：整表替换领土。 */
    public void replaceTerritory(List<Integer> regionIds) {
        territory.clear();
        if (regionIds != null) {
            for (Integer id : regionIds) {
                if (id != null) {
                    territory.add(id);
                }
            }
        }
    }
}
