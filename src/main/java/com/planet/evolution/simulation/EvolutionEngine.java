package com.planet.evolution.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.ai.NarrativeExecutor;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Region;
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

    /** 玩家文明固定为 id = 1，不参与 AI 决策（与 ChatController 一致）。 */
    private static final long PLAYER_CIV_ID = 1L;
    /** AI 世界演化的周期：每 20 年一次。 */
    private static final int AI_TICK_INTERVAL = 20;
    /** AI 世界事件/决策的稀有度（>= 60，属重大事件）。 */
    private static final int AI_EVENT_RARITY = 70;
    /** civDecisions 里「发展科技」没给 effects.tech 时的默认科技增益。 */
    private static final int AI_DECISION_TECH_GAIN = 50;
    /** AI 效果的数值上限：模型偶尔会给离谱数字，兜一下免得把模拟搞崩。 */
    private static final long AI_EFFECT_LIMIT = 1_000_000L;
    /** 研究模式 active：玩家文明不自动研究，等玩家指令。 */
    public static final String RESEARCH_MODE_ACTIVE = "active";
    /** 研究模式 passive：玩家文明自动研究（默认）。 */
    public static final String RESEARCH_MODE_PASSIVE = "passive";
    /** 研究进度：每年增加 techLevel × 该系数。 */
    private static final double RESEARCH_PROGRESS_PER_TECH = 0.1;
    /** 科技发明的稀有度（AI_EVENT 口径，>= 60 属重大事件）。 */
    private static final int RESEARCH_RARITY = AI_EVENT_RARITY;
    /** 规则事件降频系数：AI 接管演化后，灾难/战争/科技突破只作兜底（灾难 2% → 0.5%）。 */
    private static final double FALLBACK_EVENT_FACTOR = 0.25;
    /** 世界初始状态：与 WorldConfig 的 new Planet("盖亚", 15.0, 1.0) 及构造函数初值保持一致。 */
    private static final double INITIAL_FOOD = 600.0;
    private static final double INITIAL_RESOURCES = 400.0;
    private static final double INITIAL_TEMPERATURE = 15.0;
    private static final double INITIAL_HABITABILITY = 1.0;

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

    /**
     * 科技树的研究模式，默认 passive（玩家文明自动研究）。
     * active 时玩家文明停下来等指令，AI 文明两种模式下都自动研究。
     */
    private String researchMode = RESEARCH_MODE_PASSIVE;

    /** 是否启用 AI 叙事：推进年数多时关闭，避免大量异步叙事。 */
    private boolean narrateEnabled = true;

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
    // AI 叙事异步执行器：提交后立即返回，不阻塞 tick
    @Autowired
    private NarrativeExecutor narrativeExecutor;
    // 解析 AI 返回的演化 JSON（离线测试没有注入时为 null，直接跳过 AI 演化）
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * AI 回复的落脚点：AI 线程只做网络调用并把原文入队，
     * 世界改动统一由 tick 线程在 {@link #applyPendingAiReplies()} 里执行，避免两边同时改文明。
     */
    private final Queue<String> pendingAiReplies = new ConcurrentLinkedQueue<>();

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
        this.food = INITIAL_FOOD;
        this.resources = INITIAL_RESOURCES;
    }

    /** 设置是否启用 AI 叙事。 */
    public void setNarrateEnabled(boolean enabled) {
        this.narrateEnabled = enabled;
    }

    /** 推进一年。 */
    public void tick() {
        // 先把上一次 AI 演化请求的结果落地：世界状态只在 tick 线程上改
        applyPendingAiReplies();
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
        // 科技树：先涨科技、再按科技水平推进研究进度
        tickResearch();
        world.tick();
        if (world.getYear() % 100 == 0 && world.getYear() > 0) {
            narrateCentury();
        }
        // 每 20 年让 AI 驱动一次世界演化（世界事件 + 各文明决策）
        if (world.getYear() > 0 && world.getYear() % AI_TICK_INTERVAL == 0) {
            aiTick();
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
        if (narrativeExecutor == null || aiNarrator == null || worldMapper == null) {
            // 直接 new 构造的引擎（离线测试）没有注入，跳过叙事
            return;
        }
        // 时代该升就升；仅在启用叙事时提交 AI 叙事任务
        if (narrateEnabled) {
            // 参数在提交前算好，Runnable 内不再读引擎/世界状态
            final int storyYear = world.getYear();
            final String eraLabel = eraName;
            final String eraPrompt = prompt;
            narrativeExecutor.submit(() -> {
                String story = aiNarrator.narrate(eraPrompt);
                if (story != null) {
                    System.out.println("[时代晋升] " + eraLabel + "：" + story);
                    // 直接写 mapper：异步任务完成时 tick 的 drainEvents 早已结束，
                    // 写进引擎内部缓冲不会再被搬走
                    worldMapper.insertEvent(new GameEvent(storyYear, EventType.AI_NARRATIVE, story, 80));
                }
            });
        }
    }

    /** 每百年：取最近 100 年的真实事件拼 prompt，交给 AI 写叙事，并作为一条事件写入历史。 */
    private void narrateCentury() {
        if (!narrateEnabled) return;
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
        int majorCount = 0;
        for (GameEvent event : worldMapper.selectEvents()) {
            // 只取本世纪的普通事件；过往的 AI 叙事不作为素材，避免自我引用
            if (event.year() < startYear || event.year() > endYear
                    || event.type() == EventType.AI_NARRATIVE) {
                continue;
            }
            prompt.append("- 第 ").append(event.year()).append(" 年：")
                    .append(event.description()).append('\n');
            if (event.rarity() >= MajorEventRules.MAJOR_THRESHOLD) {
                majorCount++;
            }
        }
        if (majorCount == 0) {
            // 本世纪没有重大事件（稀有度 >= 60），不调用 AI
            return;
        }
        if (narrativeExecutor == null) {
            return;
        }
        // 参数在提交前算好，Runnable 内不再读引擎/世界状态
        final int storyYear = endYear;
        final String centuryPrompt = prompt.toString();
        narrativeExecutor.submit(() -> {
            String story = aiNarrator.narrate(centuryPrompt);
            if (story == null) {
                return;
            }
            System.out.println("[AI叙事] 第 " + storyYear + " 年：" + story);
            // 直接写 mapper，保证前端立即可见（不依赖后续 tick 的 drainEvents）
            worldMapper.insertEvent(new GameEvent(storyYear, EventType.AI_NARRATIVE, story, AI_NARRATIVE_RARITY));
        });
    }

    // ==================== AI 世界演化（每 20 年一次） ====================

    /**
     * AI 世界演化：一次请求同时产出「世界事件」和「各文明决策」。
     * 这里只在 tick 线程上同步拼 prompt 并提交异步任务；AI 的回复由
     * {@link #applyPendingAiReplies()} 在后续 tick 上落地，
     * 于是世界状态永远只被 tick 线程修改（AI 线程只做网络调用 + 入队）。
     */
    private void aiTick() {
        // 长推进（>100 年）时与现有 AI 叙事一样关掉，否则几百个请求会堆在队列里
        if (!narrateEnabled) {
            return;
        }
        // 直接 new 构造的引擎（离线测试）没有 Spring 注入，跳过 AI 演化
        if (aiNarrator == null || narrativeExecutor == null || worldMapper == null || objectMapper == null) {
            return;
        }
        final String prompt = buildAiTickPrompt();
        narrativeExecutor.submit(() -> {
            String reply = aiNarrator.narrate(prompt);
            if (reply != null) {
                // 只入队，绝不在这里碰世界状态
                pendingAiReplies.add(reply);
            }
        });
    }

    /** 拼 prompt：所有存活文明 + 最近 20 年事件 + 当前时代，一次要回世界事件和各文明决策。 */
    private String buildAiTickPrompt() {
        int year = world.getYear();
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器的世界导演。根据下面的世界状态，一次性输出两部分内容：\n")
                .append("1) worldEvents：接下来 20 年发生的 1~3 条世界事件（天灾、发现、思潮、战争等）\n")
                .append("2) civDecisions：每个非玩家文明在这 20 年里的一个决策\n")
                .append("只输出 JSON，不要解释，不要代码块。格式：\n")
                .append("{\"worldEvents\":[{\"name\":\"事件名\",\"description\":\"发生了什么\",")
                .append("\"affectedCivIds\":[2,3],\"effects\":{\"tech\":50,\"population\":-1000,\"resources\":2000}}],")
                .append("\"civDecisions\":[{\"civId\":2,\"decision\":\"发展科技\",\"effects\":{\"tech\":50}},")
                .append("{\"civId\":3,\"decision\":\"攻打文明-5\",\"targetCivId\":5},")
                .append("{\"civId\":4,\"decision\":\"扩张\"}]}\n")
                .append("- effects.tech=科技增减、effects.population=人口增减（作用于 affectedCivIds 里每个文明），")
                .append("effects.resources=全球资源增减；都可以为负，也可以省略\n")
                .append("- civDecisions 的 decision 只允许这三种：\"发展科技\"、\"扩张\"、\"攻打文明X\"（攻打必须给 targetCivId）\n")
                .append("- 玩家文明（id=").append(PLAYER_CIV_ID).append("）不参与决策，不要给它写 civDecisions\n")
                .append("- affectedCivIds 和 targetCivId 只能用下面列出的存活文明 id，不要编造\n\n");
        prompt.append("世界状态：\n");
        prompt.append("- 当前年份：").append(year).append('\n');
        prompt.append("- 当前时代：").append(ERAS[currentEraIndex]).append('\n');
        prompt.append("- 全球资源存量：").append(Math.round(resources)).append('\n');
        prompt.append("- 存活文明：\n");
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            prompt.append("  id=").append(civilization.getId())
                    .append("，名称=").append(civilization.getName())
                    .append("，人口=").append(civilization.getPopulation())
                    .append("，科技=").append(civilization.getTechLevel())
                    .append("，领土=").append(civilization.getTerritory().size()).append("块")
                    .append(civilization.getId() == PLAYER_CIV_ID ? "（玩家，不参与决策）" : "")
                    .append('\n');
        }
        prompt.append("- 最近 ").append(AI_TICK_INTERVAL).append(" 年的事件：\n");
        boolean hasEvent = false;
        for (GameEvent event : worldMapper.selectEvents()) {
            // 与百年叙事一致：不拿 AI 叙事当素材，避免自我引用
            if (event.year() < year - AI_TICK_INTERVAL || event.year() > year
                    || event.type() == EventType.AI_NARRATIVE) {
                continue;
            }
            prompt.append("  [").append(event.year()).append("年] ").append(event.description()).append('\n');
            hasEvent = true;
        }
        if (!hasEvent) {
            prompt.append("  （无）\n");
        }
        return prompt.toString();
    }

    /** 落地所有已到达的 AI 回复；只在 tick 线程调用，单条出错不影响其他条目。 */
    private void applyPendingAiReplies() {
        String reply;
        while ((reply = pendingAiReplies.poll()) != null) {
            try {
                applyAiReply(reply);
            } catch (Exception e) {
                System.out.println("[AI演化] 回复无法处理，已跳过：" + e.getMessage());
            }
        }
    }

    private void applyAiReply(String reply) throws Exception {
        JsonNode root = parseJsonObject(reply);
        int year = world.getYear();
        JsonNode worldEvents = root.path("worldEvents");
        if (worldEvents.isArray()) {
            for (JsonNode event : worldEvents) {
                applyWorldEvent(year, event);
            }
        }
        JsonNode civDecisions = root.path("civDecisions");
        if (civDecisions.isArray()) {
            for (JsonNode decision : civDecisions) {
                applyCivDecision(year, decision);
            }
        }
    }

    /** 容忍模型带 markdown 代码块或前后废话：截取第一个 { 到最后一个 }。 */
    private JsonNode parseJsonObject(String reply) throws Exception {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("没有找到 JSON 对象");
        }
        return objectMapper.readTree(reply.substring(start, end + 1));
    }

    /** 一条世界事件：按 affectedCivIds 套 effects，再写一条 AI_EVENT。 */
    private void applyWorldEvent(int year, JsonNode event) {
        String name = event.path("name").asText("").trim();
        String description = event.path("description").asText("").trim();
        String text = name.isEmpty() ? description
                : (description.isEmpty() ? name : name + "：" + description);
        if (text.isEmpty()) {
            return;
        }
        JsonNode effects = event.path("effects");
        long populationDelta = clamp(effects.path("population").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
        int techDelta = (int) clamp(effects.path("tech").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
        double resourceDelta = clamp(effects.path("resources").asDouble(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
        JsonNode affected = event.path("affectedCivIds");
        if (affected.isArray()) {
            for (JsonNode idNode : affected) {
                Civilization civilization = findCivilization(idNode.asLong(-1));
                if (civilization != null && civilization.getPopulation() > 0) {
                    applyEffects(year, civilization, populationDelta, techDelta);
                }
            }
        }
        // 资源是全球池，一条事件只扣/加一次
        if (resourceDelta != 0) {
            resources = clamp(resources + resourceDelta, 0, MAX_STOCK);
        }
        record(year, EventType.AI_EVENT, text, AI_EVENT_RARITY);
    }

    /** 一条文明决策：发展科技 / 扩张 / 攻打 走专门逻辑，其他按 effects 改数值。 */
    private void applyCivDecision(int year, JsonNode decision) {
        Civilization civilization = findCivilization(decision.path("civId").asLong(-1));
        // 玩家文明不参与 AI 决策；已灭亡的文明也不再行动
        if (civilization == null || civilization.getId() == PLAYER_CIV_ID
                || civilization.getPopulation() <= 0) {
            return;
        }
        String text = decision.path("decision").asText("").trim();
        JsonNode effects = decision.path("effects");
        if (text.contains("攻打") || text.contains("进攻")) {
            aiAttack(year, civilization, decision);
            return;
        }
        if (text.contains("扩张") || text.contains("占领")) {
            record(year, EventType.AI_EVENT, civilization.getName() + "选择扩张" + expandOne(civilization),
                    AI_EVENT_RARITY);
            return;
        }
        if (text.contains("科技")) {
            int gain = (int) clamp(effects.path("tech").asLong(AI_DECISION_TECH_GAIN),
                    -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
            applyEffects(year, civilization, 0, gain);
            record(year, EventType.AI_EVENT, civilization.getName() + "选择发展科技（科技 +" + gain + "）",
                    AI_EVENT_RARITY);
            return;
        }
        applyEffects(year, civilization,
                clamp(effects.path("population").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT),
                (int) clamp(effects.path("tech").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT));
        record(year, EventType.AI_EVENT, civilization.getName() + "的决策：" + (text.isEmpty() ? "调整" : text),
                AI_EVENT_RARITY);
    }

    /** 给单个文明套科技/人口增减；人口跌破灭绝线按灭亡处理（与规则事件同口径）。 */
    private void applyEffects(int year, Civilization civilization, long populationDelta, int techDelta) {
        if (techDelta != 0) {
            civilization.setTechLevel(
                    (int) clamp(civilization.getTechLevel() + (long) techDelta, 0, AI_EFFECT_LIMIT));
        }
        if (populationDelta == 0) {
            return;
        }
        long next = civilization.getPopulation() + populationDelta;
        if (next < profile.extinctionPopulation()) {
            record(year, EventType.EXTINCTION, civilization.getName() + "人口凋零，文明灭亡",
                    MajorEventRules.extinctionRarity());
            civilization.setPopulation(0L);
            return;
        }
        civilization.setPopulation(Math.min(MAX_POPULATION, next));
    }

    /** AI 决策「攻打」：复用现有战争损失逻辑 applyWarLoss 与 WAR 事件口径。 */
    private void aiAttack(int year, Civilization attacker, JsonNode decision) {
        Civilization target = findCivilization(decision.path("targetCivId").asLong(-1));
        if (target == null) {
            // targetCivId 缺失时退回从决策文本里找文明名（例如「攻打文明-5」）
            target = findByDecisionText(decision.path("decision").asText(""));
        }
        if (target == null || target.getId() == attacker.getId() || target.getPopulation() <= 0) {
            record(year, EventType.AI_EVENT, attacker.getName() + "想动武，但没找到目标", AI_EVENT_RARITY);
            return;
        }
        double severity = 0.05 + random.nextDouble() * 0.2;
        applyWarLoss(year, attacker, Math.round(attacker.getPopulation() * severity));
        applyWarLoss(year, target, Math.round(target.getPopulation() * severity));
        resources = Math.max(0, resources * 0.85);
        int jitter = random.nextInt(MajorEventRules.WAR_RARITY_JITTER + 1);
        record(year, EventType.WAR, attacker.getName() + "攻打" + target.getName() + "，双方损失人口约"
                + Math.round(severity * 100) + "%", MajorEventRules.warRarity(totalTech(), jitter));
    }

    /** 占一块与该文明接壤的无主区域，返回拼事件描述用的后缀。 */
    private String expandOne(Civilization civilization) {
        for (Region region : world.getRegionMap().all()) {
            if (region.isUnowned() && borders(civilization, region)) {
                region.setOwnerCivId((int) civilization.getId());
                civilization.addRegion(region.getId());
                return "至" + region.getName();
            }
        }
        return "，但没有接壤的无主区域";
    }

    /** 目标区域是否与该文明现有领土接壤。 */
    private boolean borders(Civilization civilization, Region target) {
        for (Integer ownedId : civilization.getTerritory()) {
            if (target.getNeighbors().contains(ownedId)) {
                return true;
            }
        }
        return false;
    }

    /** 从决策文本里认文明名（"攻打文明-5" 这种），作为 targetCivId 缺失时的兜底。 */
    private Civilization findByDecisionText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() > 0 && text.contains(civilization.getName())) {
                return civilization;
            }
        }
        return null;
    }

    private Civilization findCivilization(long id) {
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getId() == id) {
                return civilization;
            }
        }
        return null;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== 跳过时代（单人直接执行，不投票） ====================

    /** 跳过时代一次最多跨几个时代。 */
    public static final int MAX_ERA_SKIP_STEPS = 5;

    /**
     * 跳过当前时代：把科技、人口、食物、资源按目标时代的门槛比例整体放大。
     * 合法性：targetEraIndex 必须晚于当前时代、最多跨 {@link #MAX_ERA_SKIP_STEPS} 个时代、且小于 ERAS.length。
     * 缩放比例 = ERA_THRESHOLDS[target] / ERA_THRESHOLDS[current]；
     * 蒙昧时代门槛是 0，比例无从计算，此时取 1（只推时代，不放大数值）。
     *
     * @return 成功时返回一段描述文字；失败时返回错误信息
     */
    public synchronized String skipEra(int targetEraIndex) {
        if (targetEraIndex >= ERAS.length) {
            return "目标时代不存在（最大是 " + ERAS[ERAS.length - 1] + "，下标 " + (ERAS.length - 1) + "）";
        }
        if (targetEraIndex <= currentEraIndex) {
            return "目标时代必须晚于当前时代（当前：" + ERAS[currentEraIndex] + "）";
        }
        if (targetEraIndex > currentEraIndex + MAX_ERA_SKIP_STEPS) {
            return "一次最多跳 " + MAX_ERA_SKIP_STEPS + " 个时代（当前 " + ERAS[currentEraIndex]
                    + "，最远只能到 " + ERAS[Math.min(ERAS.length - 1, currentEraIndex + MAX_ERA_SKIP_STEPS)]
                    + "）";
        }

        String fromEra = ERAS[currentEraIndex];
        String toEra = ERAS[targetEraIndex];
        double fromThreshold = ERA_THRESHOLDS[currentEraIndex];
        double scale = fromThreshold <= 0 ? 1.0 : ERA_THRESHOLDS[targetEraIndex] / fromThreshold;

        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            long tech = Math.round(civilization.getTechLevel() * scale);
            civilization.setTechLevel((int) Math.min(Integer.MAX_VALUE, tech));
            long population = Math.round(civilization.getPopulation() * scale);
            civilization.setPopulation(Math.min(MAX_POPULATION, population));
        }
        food = Math.min(MAX_STOCK, food * scale);
        resources = Math.min(MAX_STOCK, resources * scale);
        currentEraIndex = targetEraIndex;

        narrateEraSkip(fromEra, toEra, scale);
        return "文明跨过漫长岁月，从" + fromEra + "进入了" + toEra
                + "（科技与人口 ×" + String.format("%.2f", scale) + "）";
    }

    /** 异步让 AI 写一段「时代跨越」叙事，作为 AI_NARRATIVE 事件写入历史（稀有度 80）。 */
    private void narrateEraSkip(String fromEra, String toEra, double scale) {
        // 直接 new 构造的引擎（离线测试）没有注入，跳过
        if (aiNarrator == null || worldMapper == null || narrativeExecutor == null) {
            return;
        }
        final int storyYear = world.getYear();
        final String prompt = "星球整体跨过了一段漫长岁月，从" + fromEra + "直接进入了" + toEra
                + "（科技与人口规模约 ×" + String.format("%.2f", scale)
                + "）。请写一段 100 字左右的历史叙事，描述这段被跨越的岁月里发生了什么，"
                + "风格像《银河系漫游指南》，只写叙事，不要分析，不要解释。";
        narrativeExecutor.submit(() -> {
            String story = aiNarrator.narrate(prompt);
            if (story != null) {
                System.out.println("[时代跨越] " + fromEra + " → " + toEra + "：" + story);
                // 与时代晋升一致：直接写 mapper，保证前端立即可见
                worldMapper.insertEvent(new GameEvent(storyYear, EventType.AI_NARRATIVE, story, 80));
            }
        });
    }

    // ==================== 新游戏（清空世界，从零开始） ====================

    /**
     * 新游戏：清空文明与历史事件、区域全部回到无主，年份/时代/食物/资源/星球参数都恢复初始值，
     * 然后按 WorldConfig 的做法重新生成 1 个「人类」文明。
     *
     * @return 给前端显示的提示文字
     */
    public synchronized String resetWorld() {
        // 文明与区域归属：clearCivilizations() 内部会同时把所有区域置为无主
        world.clearCivilizations();
        world.setYear(0);
        currentEraIndex = 0;
        food = INITIAL_FOOD;
        resources = INITIAL_RESOURCES;
        lastFoodProduction = 0;
        foodBalance = 0;
        foodRatio = 1.0;
        // 星球参数回到 WorldConfig 里的初始值（星球名不动）
        world.getPlanet().setTemperature(INITIAL_TEMPERATURE);
        world.getPlanet().setHabitability(INITIAL_HABITABILITY);
        // 历史事件：引擎缓冲区与世界存储都清空
        synchronized (events) {
            events.clear();
        }
        if (worldMapper != null) {
            worldMapper.clearEvents();
        }
        // 上一局残留的 AI 回复不能再落到新世界上
        pendingAiReplies.clear();
        // 文明 id 计数与命名计数一并归零：重开后的「人类」必须还是 id=1（玩家文明）
        nextCivilizationId = 1;
        world.resetCivilizationIdCounter();
        // 重开后的第一个文明仍是玩家（id=1）：点亮它的领土与一圈邻区，其余回到黑雾
        Civilization player = world.spawnCivilization("人类");
        world.exploreAround(player);
        return "世界已重置，从第 0 年开始。";
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

    // ==================== 科技树 ====================

    /** 当前研究模式：active（玩家等指令）或 passive（玩家自动研究）。 */
    public String getResearchMode() {
        return researchMode;
    }

    /**
     * 设置研究模式，只认 active / passive（大小写不敏感），其余值忽略。
     *
     * @return true 表示已切换；false 表示入参非法、模式保持原样
     */
    public boolean setResearchMode(String mode) {
        if (mode == null) {
            return false;
        }
        String wanted = mode.trim().toLowerCase();
        if (RESEARCH_MODE_ACTIVE.equals(wanted)) {
            researchMode = RESEARCH_MODE_ACTIVE;
            return true;
        }
        if (RESEARCH_MODE_PASSIVE.equals(wanted)) {
            researchMode = RESEARCH_MODE_PASSIVE;
            return true;
        }
        return false;
    }

    /** 是否处于主动模式（玩家文明不自动研究）。 */
    public boolean isResearchActive() {
        return RESEARCH_MODE_ACTIVE.equals(researchMode);
    }

    /**
     * 每年一次的研究推进，所有存活文明都走这里：
     * <ul>
     *   <li>没有在研究的：AI 文明按性格挑一个；玩家文明被动模式下同 AI，主动模式跳过等指令</li>
     *   <li>有在研究的：进度 += techLevel × 0.1（见 RESEARCH_PROGRESS_PER_TECH）</li>
     *   <li>进度攒够 cost：解锁、科技 + techBonus、写一条 AI_EVENT、清空当前研究</li>
     * </ul>
     */
    private void tickResearch() {
        int year = world.getYear();
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            boolean player = civilization.getId() == PLAYER_CIV_ID;
            if (civilization.getCurrentResearchId() == null) {
                // 主动模式：玩家文明的选题权在玩家手里，这里不替它选
                if (player && isResearchActive()) {
                    continue;
                }
                TechNode next = pickResearch(civilization);
                if (next == null) {
                    // 科技树已全部解锁（或没有满足前置的节点），无事可做
                    continue;
                }
                civilization.setCurrentResearchId(next.id());
                civilization.setResearchProgress(0);
            }
            TechNode node = TechTree.getById(civilization.getCurrentResearchId());
            if (node == null) {
                // 存档里的 id 不在当前科技树上（换过版本）：清掉，下一年重选
                civilization.setCurrentResearchId(null);
                civilization.setResearchProgress(0);
                continue;
            }
            civilization.setResearchProgress(civilization.getResearchProgress()
                    + civilization.getTechLevel() * RESEARCH_PROGRESS_PER_TECH);
            if (civilization.getResearchProgress() >= node.cost()) {
                completeResearch(year, civilization, node);
            }
        }
    }

    /**
     * 挑下一个研究项目：好战文明优先军事，和平文明优先经济/社会；
     * 偏好分支没有可选项时，退回全部可研究节点里随机。
     */
    private TechNode pickResearch(Civilization civilization) {
        List<TechNode> available = TechTree.getAvailable(civilization);
        if (available.isEmpty()) {
            return null;
        }
        boolean warlike = isWarlike(civilization);
        List<TechNode> preferred = new ArrayList<>();
        for (TechNode node : available) {
            boolean match = warlike
                    ? TechTree.BRANCH_MILITARY.equals(node.branch())
                    : TechTree.BRANCH_ECONOMY.equals(node.branch())
                            || TechTree.BRANCH_SOCIETY.equals(node.branch());
            if (match) {
                preferred.add(node);
            }
        }
        List<TechNode> pool = preferred.isEmpty() ? available : preferred;
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * 文明性格（用于选题偏好）：军事柱比民生柱高 5 以上算好战，低 5 以上算和平。
     * 非玩家文明的四柱默认都是 50、没有差异，此时按 id 稳定分档——
     * 偶数好战、奇数和平：既能分出两种性格，又不消耗随机数、重开一局性格也不变。
     */
    private boolean isWarlike(Civilization civilization) {
        int diff = civilization.getMilitary() - civilization.getWelfare();
        if (diff > 5) {
            return true;
        }
        if (diff < -5) {
            return false;
        }
        return civilization.getId() % 2 == 0;
    }

    /** 研究完成：解锁节点、加科技值、写一条「发明了【某某】」的世界事件。 */
    private void completeResearch(int year, Civilization civilization, TechNode node) {
        civilization.unlockTech(node.id());
        civilization.setTechLevel(civilization.getTechLevel() + node.techBonus());
        civilization.setCurrentResearchId(null);
        civilization.setResearchProgress(0);
        record(year, EventType.AI_EVENT,
                civilization.getName() + "发明了【" + node.name() + "】", RESEARCH_RARITY);
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
        // AI 每 20 年接管演化后，规则灾难只作兜底：概率打折（类地星球 2% → 0.5%）
        if (random.nextDouble() >= profile.disasterChance() * FALLBACK_EVENT_FACTOR) {
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
        // 规则战争同样降频，AI 决策里的「攻打」才是主要来源
        if (random.nextDouble() >= 0.04 * FALLBACK_EVENT_FACTOR) {
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
        // 规则科技突破同样降频，作为 AI 演化之外的兜底
        if (random.nextDouble() >= 0.03 * FALLBACK_EVENT_FACTOR) {
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
        // AI 叙事线程也会写事件，加锁保护
        synchronized (events) {
            events.add(new GameEvent(year, type, description, rarity));
        }
    }

    public List<GameEvent> getEvents() {
        synchronized (events) {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    public List<GameEvent> drainEvents() {
        synchronized (events) {
            List<GameEvent> drained = new ArrayList<>(events);
            events.clear();
            return drained;
        }
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

    /** 当前时代下标（只读，供存档使用）。 */
    public int currentEraIndex() {
        return currentEraIndex;
    }

    /** 全部时代名称（只读副本），供前端列可选时代用。 */
    public String[] eras() {
        return ERAS.clone();
    }

    /** 恢复存档用：写回食物、资源与时代下标。 */
    public void restoreState(double food, double resources, int eraIndex) {
        this.food = Math.max(0, food);
        this.resources = Math.max(0, resources);
        this.currentEraIndex = Math.max(0, Math.min(ERAS.length - 1, eraIndex));
    }
}
