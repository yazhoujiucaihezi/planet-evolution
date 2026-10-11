package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个地理区域。归属文明为 0 表示无主。 */
public class Region {

    /** 无主区域的归属 id。 */
    public static final int UNOWNED = 0;

    private final int id;
    private final String name;
    private int ownerCivId;
    private final List<Integer> neighbors;
    /** 玩家是否已探索该区域：false 表示还在黑雾里（前端盖深色遮罩）。 */
    private boolean explored;
    /** 地形类型：plains / mountain / desert / forest / tundra / jungle。 */
    private String terrain;

    public Region(int id, String name, List<Integer> neighbors) {
        this(id, name, neighbors, "plains");
    }

    public Region(int id, String name, List<Integer> neighbors, String terrain) {
        this.id = id;
        this.name = name;
        this.ownerCivId = UNOWNED;
        this.neighbors = new ArrayList<>(neighbors);
        // 默认未探索，开局由 World.exploreAround 点亮玩家领土及一圈邻区
        this.explored = false;
        this.terrain = terrain;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getOwnerCivId() {
        return ownerCivId;
    }

    public void setOwnerCivId(int ownerCivId) {
        this.ownerCivId = ownerCivId;
    }

    public List<Integer> getNeighbors() {
        return Collections.unmodifiableList(neighbors);
    }

    /** 是否无主。 */
    public boolean isUnowned() {
        return ownerCivId == UNOWNED;
    }

    /** 玩家是否已探索；false 表示黑雾。 */
    public boolean isExplored() {
        return explored;
    }

    public void setExplored(boolean explored) {
        this.explored = explored;
    }

    /** 地形类型（plains / mountain / desert / forest / tundra / jungle）。 */
    public String getTerrain() {
        return terrain;
    }

    public void setTerrain(String terrain) {
        this.terrain = terrain;
    }
}
