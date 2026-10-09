package com.planet.evolution.simulation;

/**
 * 星球参数接口。
 * 第一颗星球用 {@link EarthLikeProfile}（接近人类演化）。
 * 以后新增星球只需再写一个实现类，演化引擎无需改动。
 */
public interface PlanetProfile {

    /** 参数集名称，例如"类地星球"。 */
    String getName();

    /** 人均基础粮食产出系数；与人口承载力成正比，科技会在其上成倍放大。 */
    double foodPerCapita();

    /** 每单位人口的资源产出效率。 */
    double resourceYield();

    /** 单位人口每年消耗的资源。 */
    double resourceUpkeep();

    /** 每年科技自然增长（与时间的投入）。 */
    double techGrowth();

    /** 每年灾害发生概率。 */
    double disasterChance();

    /** 达到该科技等级后才会出现战争。 */
    int warTechThreshold();

    /** 人口低于该值时文明灭亡。 */
    long extinctionPopulation();
}
