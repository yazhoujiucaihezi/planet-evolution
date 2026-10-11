package com.planet.evolution.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** 一个文明（含部落、国家等阶段）。 */
public class Civilization {

    /** 四柱初始值。 */
    public static final int PILLAR_INITIAL = 50;
    /** 四柱下限，触底即崩溃。 */
    public static final int PILLAR_MIN = 0;
    /** 四柱上限，爆满即崩溃。 */
    public static final int PILLAR_MAX = 100;

    /**
     * 时代名称，与 {@link #ERA_THRESHOLDS} 一一对应。
     * 放在 Civilization 上，让「每个国家按自己科技算时代」和引擎的「世界平均时代」共用同一张表。
     */
    public static final String[] ERAS = {
        "蒙昧时代", "石器时代", "农业时代", "青铜时代", "铁器时代",
        "古典时代", "中世纪", "文艺复兴", "蒸汽时代", "电气时代",
        "信息时代", "核子时代", "星际时代", "星系文明", "维度文明"
    };

    /** 每个时代的最低科技阈值。 */
    public static final double[] ERA_THRESHOLDS = {
        0, 50, 150, 300, 500, 800, 1200, 1800,
        2500, 3500, 5000, 7000, 10000, 15000, 25000
    };

    private final long id;
    private String name;
    private int foundedYear;
    private long population;
    private int techLevel;
    // ==================== 五种资源点 ====================

    /** 资源类型：农业点。 */
    public static final String POINT_FOOD = "food";
    /** 资源类型：工业点。 */
    public static final String POINT_PRODUCTION = "production";
    /** 资源类型：军事点。 */
    public static final String POINT_MILITARY = "military";
    /** 资源类型：文化点。 */
    public static final String POINT_CULTURE = "culture";
    /** 资源类型：科学点。 */
    public static final String POINT_SCIENCE = "science";

    /** 全部资源类型（展示顺序：农业 / 工业 / 军事 / 文化 / 科学）。 */
    public static final List<String> POINT_TYPES =
            List.of(POINT_FOOD, POINT_PRODUCTION, POINT_MILITARY, POINT_CULTURE, POINT_SCIENCE);

    private static final Map<String, String> POINT_LABELS;
    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(POINT_FOOD, "农业");
        labels.put(POINT_PRODUCTION, "工业");
        labels.put(POINT_MILITARY, "军事");
        labels.put(POINT_CULTURE, "文化");
        labels.put(POINT_SCIENCE, "科学");
        POINT_LABELS = Collections.unmodifiableMap(labels);
    }

    /** 资源类型的中文名；认不出时原样返回，null 返回空串。 */
    public static String pointLabel(String type) {
        return type == null ? "" : POINT_LABELS.getOrDefault(type, type);
    }

    /** 该字符串是不是合法的资源类型。 */
    public static boolean isPointType(String type) {
        return type != null && POINT_LABELS.containsKey(type.trim());
    }

    /** 五种资源点（从 0 开始，每年由引擎产出）。 */
    private int foodPoints;
    private int productionPoints;
    private int militaryPoints;
    private int culturePoints;
    private int sciencePoints;

    /** 每解锁一个「主要消耗该类型」的科技节点，对应产出系数就乘这个步长。 */
    public static final double MULTIPLIER_STEP = 1.2;

    /** 五种产出系数，默认 1.0；解锁对应类型的节点后 ×1.2（正循环）。 */
    private double foodMultiplier = 1.0;
    private double productionMultiplier = 1.0;
    private double militaryMultiplier = 1.0;
    private double cultureMultiplier = 1.0;
    private double scienceMultiplier = 1.0;
    /** 该文明自己的时代下标（按自己的 techLevel 算），与世界平均时代 currentEraIndex 无关。 */
    private int eraIndex;
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

    // ==================== 性格 ====================

    /** 性格：好战。 */
    public static final String PERSONALITY_WARMONGER = "warmonger";
    /** 性格：和平。 */
    public static final String PERSONALITY_PACIFIST = "pacifist";
    /** 性格：科技至上。 */
    public static final String PERSONALITY_SCIENTIST = "scientist";
    /** 性格：商业。 */
    public static final String PERSONALITY_MERCHANT = "merchant";
    /** 性格：宗教。 */
    public static final String PERSONALITY_ZEALOT = "zealot";
    /** 性格：扩张。 */
    public static final String PERSONALITY_EXPANSIONIST = "expansionist";

    /** 全部性格（诞生时随机抽一个）。 */
    public static final List<String> PERSONALITIES = List.of(
            PERSONALITY_WARMONGER, PERSONALITY_PACIFIST, PERSONALITY_SCIENTIST,
            PERSONALITY_MERCHANT, PERSONALITY_ZEALOT, PERSONALITY_EXPANSIONIST);

    private static final Map<String, String> PERSONALITY_LABELS;
    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PERSONALITY_WARMONGER, "好战");
        labels.put(PERSONALITY_PACIFIST, "和平");
        labels.put(PERSONALITY_SCIENTIST, "科技至上");
        labels.put(PERSONALITY_MERCHANT, "商业");
        labels.put(PERSONALITY_ZEALOT, "宗教");
        labels.put(PERSONALITY_EXPANSIONIST, "扩张");
        PERSONALITY_LABELS = Collections.unmodifiableMap(labels);
    }

    /** 关系值下限 / 上限。 */
    public static final int RELATION_MIN = -100;
    /** 关系值上限。 */
    public static final int RELATION_MAX = 100;

    /** 该文明的性格（诞生时随机）。 */
    private String personality;

    /** 对其他文明的关系值：文明 id（字符串）→ -100~100，缺省 0 表示无感。 */
    private final Map<String, Integer> relations = new LinkedHashMap<>();

    public Civilization(long id, String name, int foundedYear, long population) {
        this.id = id;
        this.name = name;
        this.foundedYear = foundedYear;
        this.population = population;
        this.techLevel = 1;
        this.eraIndex = eraIndexOf(techLevel);
        this.personality = randomPersonality();
    }

    // ==================== 性格 / 关系 ====================

    /** 随机抽一个性格（诞生时用）。 */
    public static String randomPersonality() {
        return PERSONALITIES.get(ThreadLocalRandom.current().nextInt(PERSONALITIES.size()));
    }

    public String getPersonality() {
        return personality;
    }

    /** 设置性格；传 null / 空串时重新随机。 */
    public void setPersonality(String personality) {
        this.personality = (personality == null || personality.isBlank())
                ? randomPersonality()
                : personality.trim();
    }

    /** 该文明性格的中文名（供 prompt / 事件文案用）。 */
    public String personalityLabel() {
        return personalityLabelOf(personality);
    }

    /** 性格 id → 中文名，认不出时原样返回。 */
    public static String personalityLabelOf(String personality) {
        if (personality == null) {
            return "";
        }
        return PERSONALITY_LABELS.getOrDefault(personality, personality);
    }

    /** 对某文明的关系值（-100~100，缺省 0）。 */
    public int relationTo(long civId) {
        return relations.getOrDefault(String.valueOf(civId), 0);
    }

    /** 调整对某文明的关系值并夹取到 -100~100，返回调整后的值。 */
    public int adjustRelation(long civId, int delta) {
        int next = clampRelation(relationTo(civId) + delta);
        relations.put(String.valueOf(civId), next);
        return next;
    }

    /** 全部关系值（只读，键是文明 id 的字符串形式）。 */
    public Map<String, Integer> getRelations() {
        return Collections.unmodifiableMap(relations);
    }

    /** 恢复存档用：整表替换关系值。 */
    public void replaceRelations(Map<String, Integer> source) {
        relations.clear();
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                relations.put(entry.getKey(), clampRelation(entry.getValue()));
            }
        }
    }

    /** 关系值夹取。 */
    public static int clampRelation(int value) {
        return Math.max(RELATION_MIN, Math.min(RELATION_MAX, value));
    }

    // ==================== 记忆 ====================

    /** 记忆上限：超过就丢最旧的。 */
    public static final int MEMORY_LIMIT = 20;

    /** 该文明的记忆，按发生先后排列（最旧在前）。 */
    private final List<Memory> memories = new ArrayList<>();

    /**
     * 记一件事：写入记忆（超过 {@link #MEMORY_LIMIT} 丢最旧的），并把它按
     * {@code sentiment × 10} 折算成对目标文明的关系值变化（限 -100~100）。
     */
    public void remember(Memory memory) {
        if (memory == null) {
            return;
        }
        memories.add(memory);
        while (memories.size() > MEMORY_LIMIT) {
            memories.remove(0);
        }
        if (memory.targetCivId() > 0 && memory.targetCivId() != id) {
            adjustRelation(memory.targetCivId(), (int) Math.round(memory.sentiment() * 10));
        }
    }

    /** 全部记忆（只读）。 */
    public List<Memory> getMemories() {
        return Collections.unmodifiableList(memories);
    }

    /** 最近 n 条记忆，最新的排在前面（供 prompt 用）。 */
    public List<Memory> recentMemories(int n) {
        List<Memory> recent = new ArrayList<>();
        for (int i = memories.size() - 1; i >= 0 && recent.size() < n; i--) {
            recent.add(memories.get(i));
        }
        return recent;
    }

    // ==================== 国家目标 ====================

    /** 短期目标完成度上限。 */
    public static final int GOAL_PROGRESS_MAX = 100;

    /** 短期目标（每 50 年由 AI 更新一次），空串表示还没有目标。 */
    private String shortTermGoal = "";
    /** 长期目标（每 200 年由 AI 更新一次）。 */
    private String longTermGoal = "";
    /** 短期目标完成度 0~100。 */
    private int goalProgress;

    public String getShortTermGoal() {
        return shortTermGoal;
    }

    public void setShortTermGoal(String shortTermGoal) {
        this.shortTermGoal = shortTermGoal == null ? "" : shortTermGoal.trim();
    }

    public String getLongTermGoal() {
        return longTermGoal;
    }

    public void setLongTermGoal(String longTermGoal) {
        this.longTermGoal = longTermGoal == null ? "" : longTermGoal.trim();
    }

    public int getGoalProgress() {
        return goalProgress;
    }

    /** 设置完成度，自动夹到 0~{@link #GOAL_PROGRESS_MAX}。 */
    public void setGoalProgress(int goalProgress) {
        this.goalProgress = Math.max(0, Math.min(GOAL_PROGRESS_MAX, goalProgress));
    }

    /** 恢复存档用：一次性写回三个目标字段。 */
    public void replaceGoals(String shortTermGoal, String longTermGoal, Integer goalProgress) {
        setShortTermGoal(shortTermGoal);
        setLongTermGoal(longTermGoal);
        setGoalProgress(goalProgress == null ? 0 : goalProgress);
    }

    /** 恢复存档用：整表替换记忆（只保留最新的 {@link #MEMORY_LIMIT} 条）。 */
    public void replaceMemories(List<Memory> source) {
        memories.clear();
        if (source != null) {
            for (Memory memory : source) {
                if (memory != null) {
                    memories.add(memory);
                }
            }
        }
        while (memories.size() > MEMORY_LIMIT) {
            memories.remove(0);
        }
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

    // ==================== 涌现状态指标（引擎每年本地演化，不调 AI） ====================

    /** 民怨上限。 */
    public static final int MAX_UNREST = 100;
    /** 文化水平上限。 */
    public static final int MAX_CULTURE = 1000;
    /** 威望上限。 */
    public static final int MAX_PRESTIGE = 1000;
    /** 紧张度上限。 */
    public static final int MAX_TENSION = 100;

    /** 民怨 0~100：越高越容易爆发内战（>80 触发）。 */
    private int unrest;
    /** 文化水平 0~1000（注意与「文化点 culturePoints」是两回事）：>500 触发文化繁荣。 */
    private int culture;
    /** 威望 0~1000：>800 触发伟人诞生。 */
    private int prestige;
    /** 与邻国的紧张度 0~100：>90 触发外交危机。 */
    private int tension;

    /** 当年人口增长率（growPopulation 写入，供民怨判定用）；不存档。 */
    private double lastGrowthRate;
    /** 连续和平年数：每年 +1，卷入战争时清零。 */
    private int peaceYears;
    /** 本年内是否卷入过战争（状态指标判定用，判完即清零）；不存档。 */
    private boolean warThisYear;

    public int getUnrest() {
        return unrest;
    }

    public void setUnrest(int unrest) {
        this.unrest = clamp(unrest, 0, MAX_UNREST);
    }

    /** 加减民怨（结果夹在 0~上限）。 */
    public void addUnrest(int delta) {
        setUnrest(this.unrest + delta);
    }

    /** 民怨的小数余量：+0.5/年 这类小步长先攒着，满 1 再落账（不存档）。 */
    private double unrestCarry;

    /** 带小数的民怨增量（如人口挤爆承载力时每年 +0.5），攒满 1 才真正加一。 */
    public void addUnrestFractional(double amount) {
        if (amount <= 0) {
            return;
        }
        unrestCarry += amount;
        if (unrestCarry >= 1) {
            int whole = (int) Math.floor(unrestCarry);
            unrestCarry -= whole;
            addUnrest(whole);
        }
    }

    public int getCulture() {
        return culture;
    }

    public void setCulture(int culture) {
        this.culture = clamp(culture, 0, MAX_CULTURE);
    }

    /** 加减文化水平（结果夹在 0~上限）。 */
    public void addCulture(int delta) {
        setCulture(this.culture + delta);
    }

    public int getPrestige() {
        return prestige;
    }

    public void setPrestige(int prestige) {
        this.prestige = clamp(prestige, 0, MAX_PRESTIGE);
    }

    /** 加减威望（结果夹在 0~上限）。 */
    public void addPrestige(int delta) {
        setPrestige(this.prestige + delta);
    }

    public int getTension() {
        return tension;
    }

    public void setTension(int tension) {
        this.tension = clamp(tension, 0, MAX_TENSION);
    }

    /** 加减紧张度（结果夹在 0~上限）。 */
    public void addTension(int delta) {
        setTension(this.tension + delta);
    }

    /** 恢复存档用：一次性写回四个状态指标（null 按 0 处理）。 */
    public void replaceIndicators(Integer unrest, Integer culture, Integer prestige, Integer tension) {
        setUnrest(unrest == null ? 0 : unrest);
        setCulture(culture == null ? 0 : culture);
        setPrestige(prestige == null ? 0 : prestige);
        setTension(tension == null ? 0 : tension);
    }

    /** 当年人口增长率（-1~1），引擎每年写一次。 */
    public double getLastGrowthRate() {
        return lastGrowthRate;
    }

    public void setLastGrowthRate(double lastGrowthRate) {
        this.lastGrowthRate = lastGrowthRate;
    }

    /** 连续和平年数。 */
    public int getPeaceYears() {
        return peaceYears;
    }

    public void setPeaceYears(int peaceYears) {
        this.peaceYears = Math.max(0, peaceYears);
    }

    /** 又和平过了一年。 */
    public void addPeaceYear() {
        this.peaceYears = Math.min(Integer.MAX_VALUE, this.peaceYears + 1);
    }

    /** 卷入战争：和平年数清零。 */
    public void resetPeace() {
        this.peaceYears = 0;
    }

    /** 本年内是否卷入过战争。 */
    public boolean isWarThisYear() {
        return warThisYear;
    }

    public void setWarThisYear(boolean warThisYear) {
        this.warThisYear = warThisYear;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== 科技 / 科技点 / 个人时代 ====================

    public int getTechLevel() {
        return techLevel;
    }

    /** 直接设置科技值（恢复存档等用），并同步刷新个人时代下标。 */
    public void setTechLevel(int techLevel) {
        this.techLevel = Math.max(0, techLevel);
        this.eraIndex = eraIndexOf(this.techLevel);
    }

    /**
     * 科技自然增长：科技 +delta，并刷新个人时代下标。
     * 资源点由引擎每年按人口 / 领土另行产出（见 EvolutionEngine 的 collectPoints），不在这里给。
     */
    public void gainTech(int delta) {
        if (delta == 0) {
            return;
        }
        this.techLevel = Math.max(0, this.techLevel + delta);
        this.eraIndex = eraIndexOf(this.techLevel);
    }

    // ==================== 五种资源点读写 ====================

    /** 读某种资源点；类型非法时返回 0。 */
    public int getPoints(String type) {
        if (type == null) {
            return 0;
        }
        return switch (type.trim()) {
            case POINT_FOOD -> foodPoints;
            case POINT_PRODUCTION -> productionPoints;
            case POINT_MILITARY -> militaryPoints;
            case POINT_CULTURE -> culturePoints;
            case POINT_SCIENCE -> sciencePoints;
            default -> 0;
        };
    }

    /** 写某种资源点（夹在 0 ~ Integer.MAX_VALUE）。 */
    public void setPoints(String type, int value) {
        int safe = Math.max(0, value);
        if (type == null) {
            return;
        }
        switch (type.trim()) {
            case POINT_FOOD -> foodPoints = safe;
            case POINT_PRODUCTION -> productionPoints = safe;
            case POINT_MILITARY -> militaryPoints = safe;
            case POINT_CULTURE -> culturePoints = safe;
            case POINT_SCIENCE -> sciencePoints = safe;
            default -> {
                // 非法类型忽略
            }
        }
    }

    /** 加减某种资源点（结果不会为负）。 */
    public void addPoints(String type, int delta) {
        setPoints(type, getPoints(type) + delta);
    }

    /**
     * 各类型资源点的小数余量：每年产出不足 1 的部分累积到下一年，
     * 否则早期（人口少 / 科技低）那零点几个科学点会被取整吞掉，永远攒不起来。
     */
    private final Map<String, Double> pointFractions = new LinkedHashMap<>();

    /**
     * 引擎产出用：把带小数的产出累进来，整数部分进点数，小数部分留给下一年。
     *
     * @param type   资源类型
     * @param amount 已乘过产出系数的原始产出（可为小数）
     */
    public void accumulatePoints(String type, double amount) {
        if (type == null || amount <= 0 || !isPointType(type.trim())) {
            return;
        }
        String key = type.trim();
        double total = pointFractions.getOrDefault(key, 0.0) + amount;
        int whole = (int) Math.floor(total);
        pointFractions.put(key, total - whole);
        if (whole > 0) {
            addPoints(key, whole);
        }
    }

    /** 五种资源点的快照（按展示顺序，供接口 / 文案用）。 */
    public Map<String, Integer> pointMap() {
        Map<String, Integer> points = new LinkedHashMap<>();
        for (String type : POINT_TYPES) {
            points.put(type, getPoints(type));
        }
        return points;
    }

    /** 五种资源点之和（供活动度排序 / 概览展示）。 */
    public int totalPoints() {
        long total = (long) foodPoints + productionPoints + militaryPoints + culturePoints + sciencePoints;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total));
    }

    /** 读某种资源的产出系数；类型非法时返回 1.0。 */
    public double getMultiplier(String type) {
        if (type == null) {
            return 1.0;
        }
        return switch (type.trim()) {
            case POINT_FOOD -> foodMultiplier;
            case POINT_PRODUCTION -> productionMultiplier;
            case POINT_MILITARY -> militaryMultiplier;
            case POINT_CULTURE -> cultureMultiplier;
            case POINT_SCIENCE -> scienceMultiplier;
            default -> 1.0;
        };
    }

    /** 写某种资源的产出系数（至少 0）。 */
    public void setMultiplier(String type, double value) {
        double safe = Math.max(0.0, value);
        if (type == null) {
            return;
        }
        switch (type.trim()) {
            case POINT_FOOD -> foodMultiplier = safe;
            case POINT_PRODUCTION -> productionMultiplier = safe;
            case POINT_MILITARY -> militaryMultiplier = safe;
            case POINT_CULTURE -> cultureMultiplier = safe;
            case POINT_SCIENCE -> scienceMultiplier = safe;
            default -> {
                // 非法类型忽略
            }
        }
    }

    /** 解锁了「主要消耗该类型」的节点：该类型的产出系数 ×1.2（正循环加成）。 */
    public void bumpMultiplier(String type) {
        if (type == null || !isPointType(type.trim())) {
            return;
        }
        setMultiplier(type, getMultiplier(type) * MULTIPLIER_STEP);
    }

    /** 该文明自己的时代下标（0~14）。 */
    public int getEraIndex() {
        return eraIndex;
    }

    /** 恢复存档用：直接写个人时代下标（一般情况下由 techLevel 派生）。 */
    public void setEraIndex(int eraIndex) {
        this.eraIndex = Math.max(0, Math.min(ERAS.length - 1, eraIndex));
    }

    /** 该文明当前所处的时代名称（按自己的 techLevel 算）。 */
    public String currentEra() {
        return ERAS[Math.max(0, Math.min(ERAS.length - 1, eraIndex))];
    }

    /** 按科技值反查时代下标。 */
    public static int eraIndexOf(int techLevel) {
        int era = 0;
        for (int i = 1; i < ERA_THRESHOLDS.length; i++) {
            if (techLevel >= ERA_THRESHOLDS[i]) {
                era = i;
            } else {
                break;
            }
        }
        return era;
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

    /** 扩张一块领土的威望奖励（引擎与玩家指令的扩张/占领都会走到这里）。 */
    public static final int PRESTIGE_EXPANSION = 10;

    /** 占有一个区域（扩张领土：威望 +10）。 */
    public void addRegion(int regionId) {
        if (!territory.contains(regionId)) {
            territory.add(regionId);
            addPrestige(PRESTIGE_EXPANSION);
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

    // ==================== 科技树（解锁记录） ====================

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
}
