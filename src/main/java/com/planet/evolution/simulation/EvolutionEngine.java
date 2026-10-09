package com.planet.evolution.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;

import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.World;

/**
 * 世界演化引擎：本地计算，不依赖 AI。
 * 每推进一年依次处理：食物与资源、人口、科技、随机事件。
 */
public class EvolutionEngine {

    /** 单个文明人口上限，防止 long 求和溢出。 */
    private static final long MAX_POPULATION = 1_000_000_000_000L;
    /** 食物与资源的存量上限。它只限制库存缓冲，不再决定人口平衡点。 */
    private static final double MAX_STOCK = 1_000_000.0;
    /** 人均年食物需求。 */
    private static final double FOOD_DEMAND_PER_CAPITA = 0.9;
    /** 断粮时的人口年下降率。 */
    private static final double STARVATION_RATE = -0.05;
    /** 断粮阈值：粮食满足率低于该值按断粮处理。 */
    private static final double STARVATION_RATIO = 0.5;
    /** 粮食充足时的最大人口年增长率。 */
    private static final double MAX_GROWTH_RATE = 0.03;
    /** 承载力随科技上升的系数：capacity ∝ 1 + 系数 × sqrt(科技)。 */
    private static final double CAPACITY_TECH_COEFFICIENT = 5.0;
    /** 文明诞生基础概率（每年）。 */
    private static final double BASE_BIRTH_CHANCE = 0.02;
    /** 文明诞生概率下限，避免存活文明过多时直接归零。 */
    private static final double MIN_BIRTH_CHANCE = 0.001;
    /** 单次分裂可带走的的最大人口。 */
    private static final long MAX_SPLIT_POPULATION = 1000L;
    /** AI 叙事的固定稀有度：低于重大阈值 60，不触发大事模式、不高亮。 */
    private static final int AI_NARRATIVE_RARITY = 30;
    private static final double BASE_CAPACITY = 500_000;

    /** 时代名称，与 ERA_THRESHOLDS 一一对应。 */
    private static final String[] ERAS = {
        "蒙昧时代", "石器时代", "农业时代", "青铜时代", "铁器时代",
        "古典时代", "中世纪", "文艺复兴", "蒸汽时代", "电气时代",
        "信息时代", "核子时代", "星际时代", "星系文明", "维度文明"
    };

    /** 每个时代的最低科技阈值。 */
    private static final double[] ERA_THRESHOLDS = {
        0, 50, 150, 300, 500, 800, 1200, 1800,
        2500, 3500, 5000, 7000, 10000, 15000, 25000
    };

    /** 当前时代下标，初始为 0（蒙昧时代）。 */
    private int currentEraIndex = 0;

    /** 灾难类型档位，按总科技分档，索引与 DISASTER_TECH_BREAKS 对应。 */
    private static final String[][] DISASTER_TIERS = {
        {"大旱", "洪水", "瘟疫", "野兽袭击"},
        {"瘟疫", "洪水", "地震", "战争"},
        {"地震", "风暴", "能源危机"},
        {"能源危机", "AI失控", "基因事故"},
        {"维度风暴", "暗物质扰动", "星核不稳"}
    };

    /** 灾难类型档位的科技下界：300 / 1500 / 5000 / 10000。 */
    private static final double[] DISASTER_TECH_BREAKS = {300, 1500, 5000, 10000};

    // AI 叙事生成器，用于把结构化事件转成中文历史记录
    @Autowired
    private AiNarrator aiNarrator;
    // 取最近百年事件用于构造叙事 prompt
    @Autowired
    private WorldMapper worldMapper;

    private final World world;
    private final PlanetProfile profile;
    private final Random random;
    private final List<GameEvent> events = new ArrayList<>();

    private double food;
    private double resources;
    /** 当年粮食总产出，用于计算净收支。 */
    private double lastFoodProduction;
    /** 当年粮食净收支（产出 - 需求）。 */
    private double foodBalance;
    /** 当年粮食满足率：>= 1 有余粮，< 0.5 判定为饥荒。 */
    private double foodRatio = 1.0;
    private long nextCivilizationId = 1;

    public EvolutionEngine(World world, PlanetProfile profile, long seed) {
        this.world = world;
        this.profile = profile;
        this.random = new Random(seed);
        this.food = 600.0;
        this.resources = 400.0;
    }

    /** 推进一年。 */
    public void tick() {
        produce();
        int year = world.getYear();
        // 先产粮、再消耗，最后按当年真实粮食结余决定人口增减
        consume();
        checkDisaster(year);
        checkWar(year);
        checkTechBreakthrough(year);
        growPopulation(year);
        checkNewCivilization(year);
        growTech();
        world.tick();
        if (world.getYear() % 100 == 0 && world.getYear() > 0) {
            narrateCentury();
        }
        checkEraPromotion();
    }

    /** 每年检查一次：科技跨过阈值就晋升时代，并让 AI 写一段时代叙事。 */
    private void checkEraPromotion() {
        double tech = totalTech();
        int newEra = currentEraIndex;
        for (int i = currentEraIndex + 1; i < ERA_THRESHOLDS.length; i++) {
            if (tech >= ERA_THRESHOLDS[i]) {
                newEra = i;
            } else {
                break;
            }
        }
        if (newEra <= currentEraIndex) {
            return;
        }
        currentEraIndex = newEra;
        String eraName = ERAS[newEra];
        String prevEra = ERAS[newEra - 1];
        String prompt = "星球的总科技水平达到了 " + (int) tech
                + "，正式从" + prevEra + "跨入" + eraName
                + "。请写一段 100 字左右的历史叙事，描述这个时代的开端，"
                + "风格像《银河系漫游指南》，只写叙事，不要分析，不要解释。";
        String story = aiNarrator == null ? null : aiNarrator.narrate(prompt);
        if (story != null) {
            System.out.println("[时代晋升] " + eraName + "：" + story);
            record(world.getYear(), EventType.AI_NARRATIVE, story, 80);
        }
    }

    /** 每百年：取最近 100 年的真实事件拼 prompt，交给 AI 写叙事，并作为一条事件写入历史。 */
    private void narrateCentury() {
        // 直接 new 构造的引擎（如离线测试）没有 Spring 注入，跳过叙事
        if (aiNarrator == null || worldMapper == null) {
            return;
        }
        int endYear = world.getYear();
        int startYear = endYear - 99;
        StringBuilder prompt = new StringBuilder("以下是星球第 ")
                .append(startYear).append(" 到 ").append(endYear)
                .append(" 年发生的事件，请写成一段 100 字左右的历史叙事，")
                .append("风格像《银河系漫游指南》，只写叙事，不要分析，不要解释：\n");
        int count = 0;
        for (GameEvent event : worldMapper.selectEvents()) {
            // 只取本世纪的普通事件；过往的 AI 叙事不作为素材，避免自我引用
            if (event.year() < startYear || event.year() > endYear
                    || event.type() == EventType.AI_NARRATIVE) {
                continue;
            }
            prompt.append("- 第 ").append(event.year()).append(" 年：")
                    .append(event.description()).append('\n');
            count++;
        }
        if (count == 0) {
            // 本世纪没有事件，不调用 AI
            return;
        }
        String story = aiNarrator.narrate(prompt.toString());
        if (story == null) {
            return;
        }
        System.out.println("[AI叙事] 第 " + endYear + " 年：" + story);
        // 同时写入事件列表，前端历史事件即可看到
        record(endYear, EventType.AI_NARRATIVE, story, AI_NARRATIVE_RARITY);
    }

    private void produce() {
        double habitability = world.getPlanet().getHabitability();
        long population = totalPopulation();
        // 资源：总量按科技系数产出（保持原有量级与 MAX_STOCK 上限）
        double techBonus = 1 + totalTech() * 0.03;
        resources = Math.min(MAX_STOCK, resources + population * profile.resourceYield() * habitability * techBonus);
        // 粮食：逐文明计算"产出 = 人口 × 人均粮食产出 × 宜居度 × 科技加成"。
        // MAX_STOCK 只限制库存缓冲；当年的粮食满足率另按"产量 - 需求"计算，
        // 否则人口平衡点又会被固定库存上限钉死。
        double produced = 0;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            produced += civilization.getPopulation() * profile.foodPerCapita() * habitability
                    * foodTechBonus(civilization);
        }
        lastFoodProduction = produced;
        food = Math.min(MAX_STOCK, food + produced);
    }

    private void consume() {
        long total = totalPopulation();
        long demand = Math.round(total * FOOD_DEMAND_PER_CAPITA);
        resources = Math.max(0, resources - total * profile.resourceUpkeep());
        food = Math.max(0, food - demand);
        // 当年粮食净收支与满足率：> 0 表示有余粮，population 才有增长空间
        double required = Math.max(1.0, total * FOOD_DEMAND_PER_CAPITA);
        foodBalance = lastFoodProduction - required;
        foodRatio = Math.max(0.0, 1.0 + foodBalance / required);
    }

    /** 粮食科技加成：随文明科技水平上升，是承载力上移的主要来源。 */
    private double foodTechBonus(Civilization civilization) {
        return 1 + CAPACITY_TECH_COEFFICIENT * Math.sqrt(Math.max(0, civilization.getTechLevel()));
    }

    /** 单个文明的当年承载人口，由科技、宜居度与星球参数共同决定，不是固定常数。 */
    private double capacityOf(Civilization civilization) {
        double habitability = world.getPlanet().getHabitability();
        return BASE_CAPACITY * habitability * foodTechBonus(civilization);
    }

    /**
     * 人口增减：粮食满足率决定方向，科技决定的承载力决定上限。
     * 满足率 >= 1 时按接近承载力的程度平滑减速；0.5~1 之间线性过渡；低于 0.5 或彻底断粮时快速下降。
     */
    private void growPopulation(int year) {
        long total = totalPopulation();
        if (total <= 0) {
            return;
        }
        double ratio = foodRatio;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            double capacity = capacityOf(civilization);
            double saturation = capacity <= 0 ? 0
                    : capacity * capacity / (capacity * capacity + (double) civilization.getPopulation() * civilization.getPopulation());
            double rate;
            if (ratio < STARVATION_RATIO) {
                // 当年产量不足需求一半：持续下降（不再因为库存为 0 而强制下降）
                rate = STARVATION_RATE;
            } else if (ratio >= 1) {
                // 粮食充足：按接近承载力的程度平滑减速
                rate = MAX_GROWTH_RATE * saturation;
            } else {
                // 0.5 <= ratio < 1：从 -0.25 连续过渡到 0，无突变
                rate = -0.25 * (1 - ratio) / (1 - STARVATION_RATIO);
            }
            long grown = Math.round(civilization.getPopulation() * (1 + rate));
            if (grown < profile.extinctionPopulation()) {
                // 跌破灭绝线即灭亡，之后不再复活
                record(year, EventType.EXTINCTION, civilization.getName() + "人口凋零，文明灭亡",
                        MajorEventRules.extinctionRarity());
                civilization.setPopulation(0L);
                continue;
            }
            civilization.setPopulation(Math.min(MAX_POPULATION, grown));
        }
    }

    private void growTech() {
        if (totalPopulation() <= 0) {
            return;
        }
        double invest = Math.max(0, resources) / (totalPopulation() + 1.0);
        double growth = Math.max(0, profile.techGrowth() * world.getPlanet().getHabitability()
                + Math.min(1.5, invest * 0.5));
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            civilization.setTechLevel((int) Math.floor(civilization.getTechLevel() + growth));
        }
    }

    /** 按总科技档位随机取一种灾难类型：科技越高，灾难越"未来感"。 */
    private String disasterName(double tech) {
        int tier = 0;
        while (tier < DISASTER_TECH_BREAKS.length && tech >= DISASTER_TECH_BREAKS[tier]) {
            tier++;
        }
        String[] names = DISASTER_TIERS[tier];
        return names[random.nextInt(names.length)];
    }

    private void checkDisaster(int year) {
        if (random.nextDouble() >= profile.disasterChance()) {
            return;
        }
        Civilization civilization = randomAliveCivilization();
        if (civilization == null) {
            return;
        }
        double tech = totalTech();
        String name = disasterName(tech);
        double baseSeverity = 0.1 + random.nextDouble() * 0.25;
        // 科技减免：科技 0 时系数 1.0，科技 20000 时系数 0.05
        double techFactor = Math.max(0.05, 1.0 - tech / 22000.0);
        double severity = baseSeverity * techFactor;
        long left = Math.round(civilization.getPopulation() * (1 - severity));
        // 灾害同样可能把文明打到灭绝线以下，但不能把已灭亡文明复活成 1 人
        if (left < profile.extinctionPopulation()) {
            record(year, EventType.EXTINCTION, civilization.getName() + "人口凋零，文明灭亡",
                    MajorEventRules.extinctionRarity());
            civilization.setPopulation(0L);
        } else {
            civilization.setPopulation(left);
        }
        food = Math.max(0, food * (1 - severity / 2));
        record(year, EventType.DISASTER, civilization.getName() + "遭遇" + name
                + "，人口减少约" + Math.round(severity * 100) + "%", MajorEventRules.disasterRarity(severity));
    }

    private void checkWar(int year) {
        // 只按存活文明数量判断是否可能存在战争
        int aliveCount = world.aliveCivilizationCount();
        if (aliveCount < 2 || totalTech() < profile.warTechThreshold()) {
            return;
        }
        if (random.nextDouble() >= 0.04) {
            return;
        }
        Civilization a = randomAliveCivilization();
        Civilization b = randomAliveCivilization();
        if (a == null || b == null || a == b) {
            return;
        }
        double severity = 0.05 + random.nextDouble() * 0.2;
        long lossA = Math.round(a.getPopulation() * severity);
        long lossB = Math.round(b.getPopulation() * severity);
        applyWarLoss(year, a, lossA);
        applyWarLoss(year, b, lossB);
        resources = Math.max(0, resources * 0.85);
        int jitter = random.nextInt(MajorEventRules.WAR_RARITY_JITTER + 1);
        record(year, EventType.WAR, a.getName() + "与" + b.getName() + "爆发战争，双方损失人口约"
                + Math.round(severity * 100) + "%", MajorEventRules.warRarity(totalTech(), jitter));
    }

    /**
     * 战争造成的人口损失：结果不会为负。
     * 跌破灭绝线时按灭亡逻辑置 0（并入灭绝事件），不再把 0 抬回 1。
     */
    private void applyWarLoss(int year, Civilization civilization, long loss) {
        if (civilization.getPopulation() <= 0) {
            return;
        }
        long left = Math.max(0L, civilization.getPopulation() - loss);
        if (left < profile.extinctionPopulation()) {
            record(year, EventType.EXTINCTION, civilization.getName() + "人口凋零，文明灭亡",
                    MajorEventRules.extinctionRarity());
            civilization.setPopulation(0L);
            return;
        }
        civilization.setPopulation(left);
    }

    private void checkTechBreakthrough(int year) {
        if (random.nextDouble() >= 0.03) {
            return;
        }
        Civilization civilization = randomAliveCivilization();
        if (civilization == null) {
            return;
        }
        // 3~13：多数是普通突破，少数是重大突破
        int techGain = 3 + random.nextInt(11);
        civilization.setTechLevel(civilization.getTechLevel() + techGain);
        food = Math.max(0, food * 1.1);
        resources = Math.max(0, resources * 1.1);
        record(year, EventType.TECH_BREAKTHROUGH, civilization.getName() + "取得科技突破（科技 +" + techGain + "）",
                MajorEventRules.techBreakthroughRarity(techGain));
    }

    private void checkNewCivilization(int year) {
        // 星球上已无活人时不再诞生新文明
        if (year < 20 || totalPopulation() <= 0) {
            return;
        }
        // 诞生概率随存活文明数衰减：0.02 / (1 + aliveCount / 50)，下限 0.001
        int aliveCount = world.aliveCivilizationCount();
        double chance = Math.max(MIN_BIRTH_CHANCE, BASE_BIRTH_CHANCE / (1 + aliveCount / 50.0));
        if (random.nextDouble() >= chance) {
            return;
        }
        // 新文明来自某个存活文明的分裂，人口守恒
        Civilization source = randomAliveCivilization();
        if (source == null) {
            return;
        }
        long split = splitPopulation(source);
        if (split <= 0) {
            // 源文明人口不足以再分裂出一个可存活的文明
            return;
        }
        source.setPopulation(source.getPopulation() - split);
        String name = "文明-" + nextCivilizationId++;
        Civilization civilization = world.spawnCivilization(name, split);
        record(year, EventType.CIVILIZATION_BIRTH, civilization.getName() + "从" + source.getName()
                + "分裂诞生，分裂人口 " + split, MajorEventRules.civilizationBirthRarity());
    }

    /**
     * 计算可从源文明分裂出的人口：最多 {@link #MAX_SPLIT_POPULATION}，
     * 且分裂后源文明必须仍高于灭绝线。返回 0 表示不可分裂。
     */
    private long splitPopulation(Civilization source) {
        long headroom = source.getPopulation() - profile.extinctionPopulation() - 1;
        return Math.min(MAX_SPLIT_POPULATION, headroom);
    }

    private Civilization randomAliveCivilization() {
        List<Civilization> alive = new ArrayList<>();
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() > 0) {
                alive.add(civilization);
            }
        }
        return alive.isEmpty() ? null : alive.get(random.nextInt(alive.size()));
    }

    private long totalPopulation() {
        long total = 0;
        for (Civilization civilization : world.getCivilizations()) {
            total += civilization.getPopulation();
        }
        return total;
    }

    private int totalTech() {
        int total = 0;
        for (Civilization civilization : world.getCivilizations()) {
            total += civilization.getTechLevel();
        }
        return total;
    }

    private void record(int year, EventType type, String description, int rarity) {
        events.add(new GameEvent(year, type, description, rarity));
    }

    public List<GameEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<GameEvent> drainEvents() {
        List<GameEvent> drained = new ArrayList<>(events);
        events.clear();
        return drained;
    }

    public double getFood() {
        return food;
    }

    public double getResources() {
        return resources;
    }

    public PlanetProfile getProfile() {
        return profile;
    }

    /** 当前时代名称（只读）。 */
    public String currentEra() {
        return ERAS[currentEraIndex];
    }
}
