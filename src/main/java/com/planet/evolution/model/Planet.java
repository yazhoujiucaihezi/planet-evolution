package com.planet.evolution.model;

/** 星球状态。 */
public class Planet {

    private String name;
    private double temperature;
    private double habitability;

    public Planet(String name, double temperature, double habitability) {
        this.name = name;
        this.temperature = temperature;
        this.habitability = habitability;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getHabitability() {
        return habitability;
    }

    public void setHabitability(double habitability) {
        this.habitability = habitability;
    }
}
