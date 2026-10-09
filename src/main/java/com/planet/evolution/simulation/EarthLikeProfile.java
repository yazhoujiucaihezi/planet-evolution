package com.planet.evolution.simulation;

/** 类地星球参数：第一颗星球，演化路径接近人类历史。 */
public class EarthLikeProfile implements PlanetProfile {

    @Override
    public String getName() {
        return "类地星球";
    }

    @Override
    public double foodPerCapita() {
        // 人均基础粮食产出系数；与人口承载力成正比（科技会在此基础上成倍放大）
        return 1.0;
    }

    @Override
    public double resourceYield() {
        return 0.9;
    }

    @Override
    public double resourceUpkeep() {
        return 0.45;
    }

    @Override
    public double techGrowth() {
        return 0.08;
    }

    @Override
    public double disasterChance() {
        return 0.02;
    }

    @Override
    public int warTechThreshold() {
        return 12;
    }

    @Override
    public long extinctionPopulation() {
        return 200L;
    }
}
