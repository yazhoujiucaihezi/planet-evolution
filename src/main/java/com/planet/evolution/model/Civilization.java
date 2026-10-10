package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 一个文明（含部落、国家等阶段）。 */
public class Civilization {

    /** 四柱初始值。 */
    public static final int PILLAR_INITIAL = 50;
    /** 四柱下限，触底即崩溃。 */
    public static final int PILLAR_MIN = 0;
    /** 四柱上限，爆满即崩溃。 */
    public static final int PILLAR_MAX = 100;

    private final long id;
    private String name;
    private int foundedYear;
    private long population;
    private int techLevel;
    /** 该文明占有的区域 id 列表。 */
    private final List<Integer> territory = new ArrayList<>();

    /**
     * 四柱（参考《王权》）：玩家文明的四个治国维度，取值 {@link #PILLAR_MIN}~{@link #PILLAR_MAX}。
     * 非玩家文明也保留这组字段（默认 50），只是当前只有玩家会用到。
     */
    private int science = PILLAR_INITIAL;
    private int welfare = PILLAR_INITIAL;
    private int resource = PILLAR_INITIAL;
    private int military = PILLAR_INITIAL;

    /** 已解锁的科技节点 id，按解锁先后有序（只读视图见 {@link #getUnlockedTechs()}）。 */
    private final Set<String> unlockedTechs = new LinkedHashSet<>();
    /**
     * 当前正在研究的科技节点 id；null 表示没有进行中的研究。
     * 主动模式下由玩家指令设置，被动模式与 AI 文明由 EvolutionEngine 自动挑选。
     */
    private String currentResearchId;
    /** 当前研究已积累的进度点，攒到节点的 cost 即解锁。 */
    private double researchProgress;

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

    // ==================== 四柱 ====================

    public int getScience() {
        return science;
    }

    public void setScience(int science) {
        this.science = clampPillar(science);
    }

    public int getWelfare() {
        return welfare;
    }

    public void setWelfare(int welfare) {
        this.welfare = clampPillar(welfare);
    }

    public int getResource() {
        return resource;
    }

    public void setResource(int resource) {
        this.resource = clampPillar(resource);
    }

    public int getMilitary() {
        return military;
    }

    public void setMilitary(int military) {
        this.military = clampPillar(military);
    }

    /** 四柱永远落在 [PILLAR_MIN, PILLAR_MAX]，写入口统一夹一次，保证模型自洽。 */
    private static int clampPillar(int value) {
        return Math.max(PILLAR_MIN, Math.min(PILLAR_MAX, value));
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

    // ==================== 科技树 ====================

    /** 已解锁的科技节点 id（只读，按解锁顺序）。 */
    public Set<String> getUnlockedTechs() {
        return Collections.unmodifiableSet(unlockedTechs);
    }

    /** 是否已解锁某个科技节点。 */
    public boolean hasUnlockedTech(String techId) {
        return techId != null && unlockedTechs.contains(techId);
    }

    /** 解锁一个科技节点（重复解锁无副作用）。 */
    public void unlockTech(String techId) {
        if (techId != null && !techId.isBlank()) {
            unlockedTechs.add(techId.trim());
        }
    }

    /** 恢复存档用：整表替换已解锁科技。 */
    public void replaceUnlockedTechs(Collection<String> techIds) {
        unlockedTechs.clear();
        if (techIds != null) {
            for (String techId : techIds) {
                unlockTech(techId);
            }
        }
    }

    /** 正在研究的科技节点 id；null 表示没有进行中的研究。 */
    public String getCurrentResearchId() {
        return currentResearchId;
    }

    /** 设置正在研究的科技节点；传 null / 空白表示取消研究。 */
    public void setCurrentResearchId(String currentResearchId) {
        this.currentResearchId = (currentResearchId == null || currentResearchId.isBlank())
                ? null
                : currentResearchId.trim();
    }

    /** 当前研究的累计进度点。 */
    public double getResearchProgress() {
        return researchProgress;
    }

    /** 当前研究的累计进度点（不会是负数）。 */
    public void setResearchProgress(double researchProgress) {
        this.researchProgress = Math.max(0, researchProgress);
    }
}
