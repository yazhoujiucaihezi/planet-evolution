package com.planet.evolution.simulation;

/**
 * 重大事件判定：每个事件带 0~100 稀有度，本地规则计算，不使用 AI。
 * 稀有度 >= {@link #MAJOR_THRESHOLD} 视为重大事件，大事模式在此暂停。
 * 各类事件的规则相互独立，调整阈值只改这一个文件。
 */
public final class MajorEventRules {

    /** 重大事件稀有度阈值。 */
    public static final int MAJOR_THRESHOLD = 60;

    /** 科技突破的科技提升量，越大越稀有。 */
    private static final int BIG_TECH_GAIN = 3;

    /** 战争稀有度的“常态科技”基准：偏离该值越远越稀有。 */
    private static final int WAR_TYPICAL_TECH = 600;
    /** 战争稀有度的随机浮动上限，避免后期战争被永久固定为重大事件。 */
    public static final int WAR_RARITY_JITTER = 30;

    private MajorEventRules() {
    }

    /** 是否为重大事件。 */
    public static boolean isMajor(GameEvent event) {
        return event.rarity() >= MAJOR_THRESHOLD;
    }

    /** 按事件类型估算稀有度，仅用于没有明确数值的旧调用。 */
    public static int estimateRarity(EventType type) {
        return switch (type) {
            case CIVILIZATION_BIRTH -> 65;
            case DISASTER -> 40;
            case WAR -> 70;
            case TECH_BREAKTHROUGH -> 35;
            case EXTINCTION -> 100;
            case AI_NARRATIVE -> 30;
        };
    }

    /** 文明诞生。 */
    public static int civilizationBirthRarity() {
        return 65;
    }

    /** 灾害：按影响程度，损失人口越多越稀有。普通灾害 < 60，严重灾害 >= 60。 */
    public static int disasterRarity(double severity) {
        return clamp((int) Math.round(40 + 100 * severity));
    }

    /**
     * 战争基准稀有度：按与常态科技水平的偏离度计算。
     * 早期原始战争与高科技时代的大战都更罕见，中期常规冲突最普通。
     */
    public static int warRarityBase(int totalTech) {
        return clamp(50 + Math.abs(totalTech - WAR_TYPICAL_TECH) / 10);
    }

    /** 战争稀有度：基准值叠加随机浮动，使各科技阶段都可能出现重大战争。 */
    public static int warRarity(int totalTech, int jitter) {
        return clamp(warRarityBase(totalTech) + jitter);
    }

    /** 科技突破：科技提升越多越稀有。普通突破 < 60，重大突破 >= 60。 */
    public static int techBreakthroughRarity(int techGain) {
        return clamp(15 + 4 * techGain);
    }

    /** 文明灭亡：最稀有。 */
    public static int extinctionRarity() {
        return 100;
    }

    /** 规则说明，用于接口自描述。 */
    public static String ruleOf(EventType type) {
        return switch (type) {
            case CIVILIZATION_BIRTH -> "文明诞生：稀有度 " + civilizationBirthRarity();
            case EXTINCTION -> "文明灭亡：稀有度 " + extinctionRarity();
            case AI_NARRATIVE -> "AI叙事：固定稀有度 30（非重大事件）";
            case WAR -> "战争：稀有度 = 50 + |总科技 - " + WAR_TYPICAL_TECH + "| / 10 + 随机浮动(0~"
                    + WAR_RARITY_JITTER + ")";
            case DISASTER -> "灾害：稀有度 = 40 + 100 × 损失比例";
            case TECH_BREAKTHROUGH -> "科技突破：稀有度 = 15 + 4 × 科技提升";
        };
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
