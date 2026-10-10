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

    public Region(int id, String name, List<Integer> neighbors) {
        this.id = id;
        this.name = name;
        this.ownerCivId = UNOWNED;
        this.neighbors = new ArrayList<>(neighbors);
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
}
