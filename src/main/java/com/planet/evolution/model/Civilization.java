package com.planet.evolution.model;

/** 一个文明（含部落、国家等阶段）。 */
public class Civilization {

    private final long id;
    private String name;
    private int foundedYear;
    private long population;
    private int techLevel;

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
}
