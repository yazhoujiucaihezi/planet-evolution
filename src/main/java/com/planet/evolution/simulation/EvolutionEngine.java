package com.planet.evolution.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.ai.NarrativeExecutor;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Memory;
import com.planet.evolution.model.Region;
import com.planet.evolution.model.Seed;
import com.planet.evolution.model.World;
import com.planet.evolution.service.PlayerService;

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
    /** AI 世界演化的周期：每 20 年一次（normal 档）。 */
    private static final int AI_TICK_INTERVAL = 20;
    /** AI 参与度：普通。 */
    public static final String AI_LEVEL_NORMAL = "normal";
    /** AI 参与度：高（每 10 年一次，参与国家更多）。 */
    public static final String AI_LEVEL_HIGH = "high";
    /** high 档的 AI 世界演化周期。 */
    private static final int AI_TICK_INTERVAL_HIGH = 10;
    /** 一次 AI 调用最多让几个国家行动——免费模型输出有长度限制，必须分批。 */
    private static final int AI_DECISION_BATCH = 5;
    /** normal 档额外参与的「最活跃国家」数量。 */
    private static final int TOP_ACTIVE_NORMAL = 3;
    /** high 档额外参与的「最活跃国家」数量。 */
    private static final int TOP_ACTIVE_HIGH = 5;
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
    /** 科技发明的稀有度（AI_EVENT 口径，>= 60 属重大事件）。 */
    private static final int RESEARCH_RARITY = AI_EVENT_RARITY;
    /** 规则事件降频系数：AI 接管演化后，灾难/战争/科技突破只作兜底（灾难 2% → 0.5%）。 */
    private static final double FALLBACK_EVENT_FACTOR = 0.25;
    /** 世界初始状态：与 WorldConfig 的 new Planet("盖亚", 15.0, 1.0) 及构造函数初值保持一致。 */
    private static final double INITIAL_FOOD = 600.0;
    private static final double INITIAL_RESOURCES = 400.0;
    private static final double INITIAL_TEMPERATURE = 15.0;
    private static final double INITIAL_HABITABILITY = 1.0;

    /** 时代名称与阈值统一放在 Civilization 上：个人时代（每个国家）与世界平均时代共用一张表。 */
    private static final String[] ERAS = Civilization.ERAS;
    private static final double[] ERA_THRESHOLDS = Civilization.ERA_THRESHOLDS;

    /** 一局最多推进到第几年（到点即终局）。 */
    public static final int GAME_MAX_YEAR = 1000;

    /** 当前「世界平均」时代下标，初始为 0（蒙昧时代）。用于灾难档位、AI 事件、跳过时代的默认目标。 */
    private int currentEraIndex = 0;

    /** 整局是否已结束（到 1000 年上限，或玩家文明灭亡）。 */
    private boolean gameOver = false;
    /** 终局原因，未结束时为 null。 */
    private String gameOverReason = null;
    /**
     * 本次终局是否由「玩家控制的文明全灭」造成。
     * 只用于区分 1000 年上限的终局：前者可在玩家切换到存活文明后恢复（见 {@link #checkPlayerExtinction(int)}）。
     */
    private boolean gameOverByExtinction = false;

    /**
     * 科技树的研究模式，默认 passive（玩家文明自动研究）。
     * active 时玩家文明停下来等指令，AI 文明两种模式下都自动研究。
     */
    private String researchMode = RESEARCH_MODE_PASSIVE;

    /**
     * AI 参与度，默认 normal。
     * normal：每 20 年驱动一次世界；high：每 10 年一次，且参与决策的国家更多。
     * 两条都保持「一次 AI 调用最多 5 个国家」，免费模型扛不住更大的批量。
     */
    private String aiLevel = AI_LEVEL_NORMAL;

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
     * 在线玩家服务：终局判定需要知道「当前被玩家控制的文明」有哪些（多玩家时不能再看死 id=1）。
     * 直接 new 出来的引擎（离线测试）没有注入，此时为 null，退回旧的 id=1 口径。
     */
    @Autowired(required = false)
    private PlayerService playerService;

    /**
     * AI 回复的落脚点：AI 线程只做网络调用并把原文入队，
     * 世界改动统一由 tick 线程在 {@link #applyPendingAiReplies()} 里执行，避免两边同时改文明。
     */
    private final Queue<String> pendingAiReplies = new ConcurrentLinkedQueue<>();

    /**
     * 因果种子列表：种下后每年检查是否该发芽。
     * 只在 tick 线程做发芽判定与移除；AI 线程只做网络调用，回复经由
     * {@link #pendingSprouts} 入队，交给 tick 线程落地（与 pendingAiReplies 同一套思路）。
     */
    private final List<Seed> seeds = new ArrayList<>();

    /** 已到达的 AI 发芽回复，等 tick 线程落地。 */
    private final Queue<SproutReply> pendingSprouts = new ConcurrentLinkedQueue<>();

    /** 一条发芽请求的结果：种子本身 + AI 生成的后果 JSON 原文。 */
    private record SproutReply(Seed seed, String reply) {
    }

    /** 因果种子的稀有度门槛：与重大事件一致（>= 60 才种、才发芽成大事件）。 */
    private static final int SEED_MIN_RARITY = MajorEventRules.MAJOR_THRESHOLD;

    /** 种子列表容量上限，兜底防止无限累积。 */
    private static final int MAX_SEEDS_TOTAL = 30;

    // ==================== 国家目标 ====================

    /** 短期目标更新周期（年）。 */
    private static final int GOAL_UPDATE_INTERVAL = 50;
    /** 长期目标更新周期（年）。 */
    private static final int LONG_GOAL_UPDATE_INTERVAL = 200;
    /** 一次 AI 调用最多给几个国家定目标（免费模型输出有长度限制，必须分批）。 */
    private static final int GOALS_PER_BATCH = 5;
    /** 目标达成时奖励的科学点（原本的「科技点」已拆成五种资源点，这里给科学点）。 */
    private static final int GOAL_REWARD_SCIENCE_POINTS = 50;

    /** 已到达的目标更新回复，等 tick 线程落地（与 pendingAiReplies 同一套思路）。 */
    private final Queue<GoalReply> pendingGoals = new ConcurrentLinkedQueue<>();

    /** 一批目标请求的结果：是否需要连长期目标一起更新 + AI 返回的 JSON 原文。 */
    private record GoalReply(boolean refreshLong, String reply) {
    }

    private final World world;
    private final PlanetProfile profile;
    private final Random random;
    private final List<GameEvent> events = new ArrayList<>();
    /** 加锁用的锁。 */
    private final ReentrantLock tickLock = new ReentrantLock();

    /** 抢 tick 锁的最长等待：这个时间内拿不到就跳过这次推进（用户操作则回「世界正忙」）。 */
    private static final long LOCK_WAIT_MILLIS = 500L;
    /** 抢 tick 锁的重试间隔。 */
    private static final long LOCK_RETRY_MILLIS = 50L;
    /** 抢不到锁时的提示。 */
    private static final String BUSY_MESSAGE = "世界正忙，请重试";

    /**
     * 抢 tick 锁：每 {@link #LOCK_RETRY_MILLIS} ms 重试一次，最多等 {@link #LOCK_WAIT_MILLIS} ms；
     * 仍拿不到返回 false。
     *
     * <p>原来直接用 {@code tryLock(2, SECONDS)} 阻塞 2 秒——由于锁内已经不含 AI 调用（见
     * {@link #tickSafe()}），实际竞争窗口是毫秒级的，等 500ms 足够，等不到就说明真有人在做长操作。
     */
    private boolean acquireTickLock() throws InterruptedException {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
        do {
            if (tickLock.tryLock(LOCK_RETRY_MILLIS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

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

    /**
     * 推进一年（线程安全）。500ms 内拿不到锁就直接跳过这一轮。
     *
     * <p>锁只覆盖计算与状态落地：这一年的 AI 请求在 {@link #aiTick()} 里只做「拼 prompt + 提交异步任务」，
     * 真正的网络调用跑在 {@code narrativeExecutor} 线程上、不持锁；AI 结果下一轮由
     * {@link #applyPendingAiReplies()} 在持锁状态下落地。所以 tick 绝不会因为 AI 慢而压住锁。
     */
    public boolean tickSafe() {
        try {
            if (!acquireTickLock()) {
                return false;
            }
            try {
                tickImpl();
                return true;
            } finally {
                tickLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 推进一年（内部实现，不含加锁）。 */
    private void tickImpl() {
        if (world.getYear() >= GAME_MAX_YEAR) {
            setGameOver("你的国家活到了第 " + GAME_MAX_YEAR + " 年");
            return;
        }
        // 终局复查：上次若是「玩家控制的文明全灭」判的终局，玩家可能已切换到别的存活文明 → 这里可恢复
        checkPlayerExtinction(world.getYear());
        if (gameOver) {
            return;
        }
        // 已结束（到 1000 年上限或玩家已灭亡）：世界不再推进
        // 先把上一次 AI 演化请求的结果落地：世界状态只在 tick 线程上改
        applyPendingAiReplies();
        // 再把已到达的因果种子发芽结果落地
        applyPendingSprouts();
        // 目标更新结果同样只在 tick 线程落地
        applyPendingGoals();
        produce();
        int year = world.getYear();
        // 先产粮、再消耗，最后按当年真实粮食结余决定人口增减
        consume();
        checkDisaster(year);
        checkWar(year);
        checkTechBreakthrough(year);
        growPopulation(year);
        // 状态指标：用本年的人口增长与战争状态更新民怨 / 文化 / 威望 / 紧张度
        updateStateIndicators(year);
        // 涌现事件：阈值由代码判定，命中了才请 AI 写叙事
        checkEmergentEvents(year);
        // 混乱度：和平衰减 + 各档位对玩家的反应
        applyChaosEffects(year);
        checkNewCivilization(year);
        growTech();
        // 五种资源点：按领土 / 人口 / 和平年数产出（并消化本年的战争标记）
        collectPoints();
        // 科技树：资源点够就解锁节点
        tickResearch();
        // 因果种子：到期的种子发芽（异步请 AI 生成后果，下一 tick 落地）
        tickSeeds(year);
        // 国家目标：每年推进完成度，每 50 / 200 年请 AI 更新目标
        tickGoals(year);
        world.tick();
        if (world.getYear() % 100 == 0 && world.getYear() > 0) {
            narrateCentury();
        }
        // 每 N 年（normal 20 / high 10）让 AI 驱动一次世界演化（世界事件 + 各文明决策）
        if (world.getYear() > 0 && world.getYear() % aiTickInterval() == 0) {
            aiTick();
        }
        checkEraPromotion();
        // AI 主动外交：每年独立掷骰，随机触发（不是固定周期）
        checkDiplomacy(world.getYear());
        // 终局判定：玩家当前控制的文明全灭才结束
        checkPlayerExtinction(world.getYear());
    }

    /** 兼容旧接口，直接执行。 */
    public void tick() {
        tickSafe();
    }

    /**
     * 终局判定：按「玩家当前控制的文明」判定，不再写死 id=1。
     * 只要还有任意一个被玩家控制的文明活着，游戏就继续；全部已灭亡才算终局。
     *
     * <ul>
     *   <li>多玩家：某个玩家的文明灭了，但别的玩家还活着 → 不结束；</li>
     *   <li>切换文明：判定依据是「当前控制的文明」，切过去的文明还活着就不会触发终局；</li>
     *   <li>玩家控制的 id 还没有对应文明（刚加入、该 id 尚未诞生）→ 不算灭亡，不触发终局；</li>
     *   <li>没有在线玩家（离线测试 / 全员离线）→ 退回旧口径，只看文明 id=1。</li>
     * </ul>
     *
     * <p>本方法按当前状态重新求值，不是一次性闩锁：若先前因全灭判过终局，
     * 之后玩家切换/接入了一个存活文明，这里会撤销终局、世界继续演化。
     */
    private void checkPlayerExtinction(int year) {
        if (world.getCivilizations().isEmpty()) {
            return;   // 空世界不判定（等新游戏生成玩家）
        }
        Civilization firstExisting = null;
        boolean anyAlive = false;
        for (long civId : controlledCivIds()) {
            Civilization civilization = world.findCivilization(civId);
            if (civilization == null) {
                continue;   // 该 id 的文明还没诞生：视作待观察，不算灭亡
            }
            if (firstExisting == null) {
                firstExisting = civilization;
            }
            if (civilization.getPopulation() > 0) {
                anyAlive = true;
                break;
            }
        }
        if (anyAlive) {
            // 还有玩家控制的文明活着：撤销「因全灭」判下的终局，游戏继续
            clearExtinctionGameOver();
            return;
        }
        if (firstExisting != null) {
            int founded = firstExisting.getFoundedYear();
            setGameOver("你的国家在 Y" + year + " 年灭亡，存活了 " + Math.max(0, year - founded) + " 年", true);
        }
    }

    /**
     * 当前被玩家控制的文明 id：优先取在线玩家所控制的；没有在线玩家时退回 id=1（兼容离线 / 单人）。
     */
    private List<Long> controlledCivIds() {
        if (playerService == null) {
            return List.of(PLAYER_CIV_ID);
        }
        List<Integer> online = playerService.controlledCivIds();
        if (online.isEmpty()) {
            return List.of(PLAYER_CIV_ID);
        }
        List<Long> ids = new ArrayList<>();
        for (Integer id : online) {
            if (id != null && id > 0) {
                ids.add(id.longValue());
            }
        }
        return ids.isEmpty() ? List.of(PLAYER_CIV_ID) : ids;
    }

    /** 标记终局：gameOver 置位、记下原因，并写一条终局事件。 */
    private void setGameOver(String reason) {
        setGameOver(reason, false);
    }

    /**
     * 标记终局。
     *
     * @param byExtinction 是否由「玩家控制的文明全灭」造成（决定后续能否被撤销）
     */
    private void setGameOver(String reason, boolean byExtinction) {
        if (gameOver) {
            return;
        }
        gameOver = true;
        gameOverReason = reason;
        gameOverByExtinction = byExtinction;
        record(world.getYear(), EventType.AI_EVENT, "【终局】" + reason, 100);
    }

    /** 撤销「因玩家文明全灭」判下的终局（玩家重新掌握了一个存活文明）。 */
    private void clearExtinctionGameOver() {
        if (!gameOver || !gameOverByExtinction) {
            return;
        }
        gameOver = false;
        gameOverReason = null;
        gameOverByExtinction = false;
        record(world.getYear(), EventType.AI_EVENT,
                "【终局撤销】玩家重新掌握了一个存活的文明，世界继续演化。", 100);
    }

    // ==================== 终局状态（对外 / 存档） ====================

    /** 整局是否已结束。 */
    public boolean isGameOver() {
        return gameOver;
    }

    /** 终局原因；未结束时为 null。 */
    public String getGameOverReason() {
        return gameOverReason;
    }

    /**
     * 恢复存档用：写回终局状态。存档里分不清终局原因，按「可能是全灭终局」处理——
     * 若是 1000 年上限的终局，年份判断会在 tick 开头先拦下，不会被误撤销。
     */
    public void restoreGameOver(boolean over, String reason) {
        this.gameOver = over;
        this.gameOverReason = reason;
        this.gameOverByExtinction = over;
    }

    /**
     * 每年检查一次：世界**平均科技**跨过阈值就晋升时代，并让 AI 写一段时代叙事。
     *
     * <p>关键：{@link Civilization#ERA_THRESHOLDS} 是「单个文明」的科技阈值（个人时代也用它），
     * 所以这里只能拿**平均科技**（{@link #averageTechLevel()}）去比，绝不能用所有文明科技的
     * **总和**（{@link #totalTech()}）——总和是「单国量级 × 国家数」，开局十几国一乘
     * （中世纪开局每国 1200，14 国就是 16800）就会直接顶穿阈值表，一年之内跳到「维度文明」。
     */
    private void checkEraPromotion() {
        double tech = averageTechLevel();
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
        String prompt = "星球平均科技水平达到了 " + (int) tech
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

    // ==================== AI 参与度 ====================

    /** 当前 AI 参与度（normal / high）。 */
    public String getAiLevel() {
        return aiLevel;
    }

    /**
     * 设置 AI 参与度，只认 normal / high（大小写不敏感），其余值忽略。
     *
     * @return true 表示已切换；false 表示入参非法、档位保持原样
     */
    public boolean setAiLevel(String level) {
        if (level == null) {
            return false;
        }
        String wanted = level.trim().toLowerCase();
        if (AI_LEVEL_NORMAL.equals(wanted)) {
            aiLevel = AI_LEVEL_NORMAL;
            return true;
        }
        if (AI_LEVEL_HIGH.equals(wanted)) {
            aiLevel = AI_LEVEL_HIGH;
            return true;
        }
        return false;
    }

    /** 当前档位下 AI 世界演化的间隔年数（normal 20 / high 10）。 */
    public int aiTickInterval() {
        return AI_LEVEL_HIGH.equals(aiLevel) ? AI_TICK_INTERVAL_HIGH : AI_TICK_INTERVAL;
    }

    /** 当前档位下额外纳入的「最活跃国家」数量。 */
    private int topActiveCount() {
        return AI_LEVEL_HIGH.equals(aiLevel) ? TOP_ACTIVE_HIGH : TOP_ACTIVE_NORMAL;
    }

    // ==================== AI 世界演化（默认每 20 年一次） ====================

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
        List<Civilization> targets = aiDecisionTargets();
        if (targets.isEmpty()) {
            return;
        }
        // 分批：一次 AI 调用最多 5 个国家，免费模型输出多了会被截断
        for (int i = 0; i < targets.size(); i += AI_DECISION_BATCH) {
            List<Civilization> batch = new ArrayList<>(
                    targets.subList(i, Math.min(targets.size(), i + AI_DECISION_BATCH)));
            final String prompt = buildAiTickPrompt(batch);
            narrativeExecutor.submit(() -> {
                try {
                    String reply = aiNarrator.narrate(prompt);
                    if (reply != null) {
                        // 只入队，绝不在这里碰世界状态
                        pendingAiReplies.add(reply);
                    }
                } catch (Exception e) {
                    // 队列满 / 限流 / 网络失败：静默跳过这次，下轮再来
                }
            });
        }
    }

    // ==================== AI 主动外交（每年随机触发） ====================
    //
    // 与「每 20 年一次」的 aiTick 不同：外交是**每年独立掷一次骰子**，触发时间不固定，
    // 概率只和「被随机选中的那个接壤国家和玩家的关系」有关。纯本地逻辑，不依赖 AI、
    // 不占 AI 调用配额，所以即使关掉叙事也照样触发。

    /** 外交每年触发的基础概率：1%。 */
    private static final double DIPLOMACY_BASE_CHANCE = 0.01;
    /** 盟友（关系 &gt; 50）/ 死敌（关系 &lt; -50）的额外触发概率：+2%。 */
    private static final double DIPLOMACY_EXTREME_EXTRA = 0.02;
    /** 盟友阈值：关系 &gt; 50。 */
    private static final int DIPLOMACY_ALLY_RELATION = 50;
    /** 死敌阈值：关系 &lt; -50。 */
    private static final int DIPLOMACY_RIVAL_RELATION = -50;
    /** 友好档下界：关系 20~50。 */
    private static final int DIPLOMACY_FRIEND_RELATION = 20;
    /** 敌意档上界：关系 -50~-20。 */
    private static final int DIPLOMACY_HOSTILE_RELATION = -20;

    /** 一次外交动作：关系越好越友善，越差越强硬；rarity 决定前端是否当重大事件高亮。 */
    public enum DiplomacyAction {
        ALLIANCE("结盟", 75, "请求结盟"),
        GIFT("送礼", 60, "送来礼物"),
        MARRIAGE("联姻", 65, "请求联姻"),
        TRADE("通商", 65, "请求通商"),
        AID("请求援助", 60, "请求援助"),
        REQUEST("提要求", 55, "提出要求"),
        PROBE("试探", 50, "前来试探"),
        THREATEN("威胁", 70, "出言威胁"),
        ULTIMATUM("最后通牒", 85, "下最后通牒"),
        WAR("宣战", 90, "向你宣战");

        private final String label;
        private final int rarity;
        private final String summary;

        DiplomacyAction(String label, int rarity, String summary) {
            this.label = label;
            this.rarity = rarity;
            this.summary = summary;
        }

        /** 动作中文名（写事件 / 提示用）。 */
        public String getLabel() {
            return label;
        }

        /** 事件稀有度：通商 65、结盟 75、宣战 90、最后通牒 85，其余按友好/敌意程度给出。 */
        public int getRarity() {
            return rarity;
        }

        /** 一句话摘要（前端提示条用，如「请求结盟」）。 */
        public String getSummary() {
            return summary;
        }
    }

    /**
     * 一条待玩家回应的外交。
     *
     * @param civId       来使的文明 id
     * @param civName     来使的文明名（如「文明-3」）
     * @param action      动作枚举名，供 ChatController 还原成 {@link DiplomacyAction}
     * @param actionLabel 动作中文名
     * @param summary     一句话摘要（前端提示条用）
     * @param message     写进历史的完整事件文本
     * @param year        发生年份
     */
    public record PendingDiplomacy(long civId, String civName, String action, String actionLabel,
            String summary, String message, int year) {
    }

    /** 当前待回应的外交（没有则 null）。只在 tick 线程写，HTTP 线程读，故 volatile。 */
    private volatile PendingDiplomacy pendingDiplomacy;

    /** 当前待回应的外交；没有则返回 null。 */
    public PendingDiplomacy getPendingDiplomacy() {
        return pendingDiplomacy;
    }

    /** 清掉待回应外交（玩家回应后、或对方已灭时调用）。 */
    public void clearDiplomacy() {
        pendingDiplomacy = null;
    }

    /**
     * 每年一次的 AI 主动外交判定（随机，不是固定周期）。
     *
     * <p>流程：找出与玩家接壤的国家 → 随机挑一个 → 按它和玩家的关系算触发概率
     * （基础 1%，盟友 / 死敌再 +2%，中立只有 1%）→ 命中后按关系分档决定它来干什么 →
     * 写一条 {@link EventType#AI_EVENT} 并把这条外交挂起等玩家回应。
     */
    private void checkDiplomacy(int year) {
        Civilization player = world.findCivilization(PLAYER_CIV_ID);
        if (player == null || player.getPopulation() <= 0) {
            return;
        }
        List<Civilization> neighbors = borderingCivilizations(player);
        if (neighbors.isEmpty()) {
            return;
        }
        // 随机挑一个和玩家接壤的国家当来使
        Civilization envoy = neighbors.get(random.nextInt(neighbors.size()));
        int relation = player.relationTo(envoy.getId());

        // 概率：基础 1%；盟友（>50）/ 死敌（<-50）额外 +2%；中立（含 ±50 边界）只有 1%
        double chance = DIPLOMACY_BASE_CHANCE;
        if (relation > DIPLOMACY_ALLY_RELATION || relation < DIPLOMACY_RIVAL_RELATION) {
            chance += DIPLOMACY_EXTREME_EXTRA;
        }
        if (random.nextDouble() >= chance) {
            return;
        }

        DiplomacyAction action = diplomacyActionFor(relation);
        String message = diplomacyMessage(envoy.getName(), action);
        record(year, EventType.AI_EVENT, message, action.getRarity());
        pendingDiplomacy = new PendingDiplomacy(envoy.getId(), envoy.getName(), action.name(),
                action.getLabel(), action.getSummary(), message, year);
    }

    /** 按关系值选本次外交动作：>50 结盟档，20~50 友好档，-20~20 中立档，-50~-20 敌意档，<-50 宣战。 */
    private DiplomacyAction diplomacyActionFor(int relation) {
        if (relation > DIPLOMACY_ALLY_RELATION) {
            return pickDiplomacy(DiplomacyAction.ALLIANCE, DiplomacyAction.GIFT, DiplomacyAction.MARRIAGE);
        }
        if (relation > DIPLOMACY_FRIEND_RELATION) {
            return pickDiplomacy(DiplomacyAction.TRADE, DiplomacyAction.AID);
        }
        if (relation >= DIPLOMACY_HOSTILE_RELATION) {
            return pickDiplomacy(DiplomacyAction.REQUEST, DiplomacyAction.PROBE);
        }
        if (relation >= DIPLOMACY_RIVAL_RELATION) {
            return pickDiplomacy(DiplomacyAction.THREATEN, DiplomacyAction.ULTIMATUM);
        }
        return DiplomacyAction.WAR;
    }

    /** 从候选动作里随机挑一个。 */
    private DiplomacyAction pickDiplomacy(DiplomacyAction... options) {
        return options[random.nextInt(options.length)];
    }

    /** 拼一条外交事件文本，风格与其它 AI_EVENT 一致。 */
    private static String diplomacyMessage(String civName, DiplomacyAction action) {
        return switch (action) {
            case ALLIANCE -> "【" + civName + "】遣使来朝，请求与【你】缔结同盟。";
            case GIFT -> "【" + civName + "】送来礼物，希望与【你】交好通商。";
            case MARRIAGE -> "【" + civName + "】派遣使团，请求与【你】联姻。";
            case TRADE -> "【" + civName + "】的商队抵达，请求与【你】通商。";
            case AID -> "【" + civName + "】遣使求援，希望【你】伸出援手。";
            case REQUEST -> "【" + civName + "】的使者提出要求，试探【你】的态度。";
            case PROBE -> "【" + civName + "】派来密使，试探【你】的虚实。";
            case THREATEN -> "【" + civName + "】的使者出言威胁，要【你】让步。";
            case ULTIMATUM -> "【" + civName + "】的使者要求你归还争议领土，否则宣战。";
            case WAR -> "【" + civName + "】撕毁和约，向你宣战！";
        };
    }

    // ==================== 五种资源点：每年产出（纯本地，不调 AI） ====================

    /** 一次科技突破（解锁节点）滋养的文化水平。 */
    private static final int CULTURE_ON_TECH_BREAKTHROUGH = 10;

    /**
     * 每年为每个存活文明结算五种资源点：
     * <pre>
     * 农业点 += 领土数 × 3   + 人口 / 10000
     * 工业点 += 领土数 × 2   + 人口 / 50000
     * 军事点 += 领土数 × 1   + 人口 / 100000
     * 文化点 += 领土数 × 1   + 连续和平年数 × 0.5
     * 科学点 += 人口 / 30000 + 科技 / 500
     * </pre>
     * 每项再乘上该文明对应的产出系数（解锁「主要消耗该类型」的科技节点时 ×1.2，正循环）。
     * 结算完把本年的战争标记消化掉：和平年数 +1（若本年打过仗则清零，见 {@link #noteWar}）。
     */
    private void collectPoints() {
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            double territory = civilization.getTerritory().size();
            double population = civilization.getPopulation();
            double tech = civilization.getTechLevel();
            addPoints(civilization, Civilization.POINT_FOOD, territory * 3 + population / 10000.0);
            addPoints(civilization, Civilization.POINT_PRODUCTION, territory * 2 + population / 50000.0);
            addPoints(civilization, Civilization.POINT_MILITARY, territory + population / 100000.0);
            addPoints(civilization, Civilization.POINT_CULTURE,
                    territory + civilization.getPeaceYears() * 0.5);
            addPoints(civilization, Civilization.POINT_SCIENCE, population / 30000.0 + tech / 500.0);
            // 本年没打仗才算又和平过了一年
            if (civilization.isWarThisYear()) {
                civilization.resetPeace();
            } else {
                civilization.addPeaceYear();
            }
            civilization.setWarThisYear(false);
        }
    }

    /** 把产出（按系数折算后）加到某种资源点上；小数部分留到下一年，不会被取整吞掉。 */
    private static void addPoints(Civilization civilization, String type, double raw) {
        double gained = raw * civilization.getMultiplier(type);
        if (gained <= 0) {
            return;
        }
        civilization.accumulatePoints(type, gained);
    }

    // ==================== 涌现状态指标（每年本地演化，不调 AI） ====================

    /** 资源充足线：四柱里的「资源」柱不低于它就算资源充足。 */
    private static final int RESOURCE_ENOUGH_PILLAR = 30;
    /** 人口年增长率超过它算「增长过快」。 */
    private static final double FAST_GROWTH_RATE = 0.02;
    /** 人口超过承载力这个比例就算「挤爆了」，民怨额外 +0.5/年。 */
    private static final double CROWDING_RATIO = 0.9;
    /** 邻国实力「接近」的比值区间。 */
    private static final double TENSION_CLOSE_LOW = 0.7;
    private static final double TENSION_CLOSE_HIGH = 1.4;
    /** 打赢一场仗的威望奖励。 */
    private static final int PRESTIGE_WIN_WAR = 30;
    /** 被灭国的威望损失。 */
    private static final int PRESTIGE_LOSE_NATION = 50;
    /** 建新城（扩张一块领土）的威望奖励。 */
    private static final int PRESTIGE_NEW_CITY = 20;
    /** 威望每 100 年自然衰减 2。 */
    private static final int PRESTIGE_DECAY_PER_CENTURY = 2;
    /** 一次科技突破的威望奖励。 */
    private static final int PRESTIGE_ON_TECH_BREAKTHROUGH = 15;

    /**
     * 每年更新四个状态指标（纯本地，不调 AI）：
     * <pre>
     * 民怨：人口增长过快 +1；资源不足 +2；卷入战争 +3；和平且资源充足 -1
     * 文化：和平 +1；卷入战争 -5；科技突破 +10（在解锁节点 / 科技突破处给）
     * 威望：打赢仗 +30、被灭国 -50（在战争结算处给）；建新城 +20（在扩张处给）；每 100 年衰减 5
     * 紧张度：邻国实力接近 +1；实力差距大 -1；卷入战争 +20
     * </pre>
     * 本方法<b>只读不消费</b> {@code warThisYear}，由 {@link #collectPoints()} 在年后统一清除。
     */
    private void updateStateIndicators(int year) {
        if (year > 0 && year % 100 == 0) {
            for (Civilization civilization : world.getCivilizations()) {
                civilization.addPrestige(-PRESTIGE_DECAY_PER_CENTURY);
            }
        }
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            boolean atWar = civilization.isWarThisYear();
            boolean resourceEnough = civilization.getResource() >= RESOURCE_ENOUGH_PILLAR;

            // 人口挤爆承载力：额外 +0.5/年（小步长走小数余量，攒满 1 再落账）；
            // 挤爆了的国家即使在和平年也得不到「相安无事」的缓解
            boolean crowded = civilization.getPopulation() > capacityOf(civilization) * CROWDING_RATIO;
            // 民怨
            int unrestDelta = 0;
            if (civilization.getLastGrowthRate() > FAST_GROWTH_RATE) {
                unrestDelta += 1;
            }
            if (!resourceEnough) {
                unrestDelta += 2;
            }
            if (atWar) {
                unrestDelta += 3;
            } else if (resourceEnough && !crowded) {
                unrestDelta -= 1;
            }
            if (crowded) {
                civilization.addUnrestFractional(0.5);
            }
            // 世界混乱度高：国内也更躁动
            if (chaosLevel > CHAOS_TIER_PANIC) {
                unrestDelta += 1;
            }
            civilization.addUnrest(unrestDelta);

            // 文化水平
            civilization.addCulture(atWar ? -5 : 1);

            // 紧张度
            civilization.addTension(tensionDelta(civilization, atWar));
        }
    }

    /** 紧张度变化：卷入战争 +20；邻国里有人实力接近再 +1，全都差距悬殊则 -1。 */
    private int tensionDelta(Civilization civilization, boolean atWar) {
        int delta = atWar ? 20 : 0;
        List<Civilization> neighbors = borderingCivilizations(civilization);
        if (neighbors.isEmpty()) {
            return delta;
        }
        boolean anyClose = false;
        for (Civilization neighbor : neighbors) {
            double ratio = powerOf(civilization) / powerOf(neighbor);
            if (ratio >= TENSION_CLOSE_LOW && ratio <= TENSION_CLOSE_HIGH) {
                anyClose = true;
                break;
            }
        }
        return delta + (anyClose ? 1 : -1);
    }

    /**
     * 记一次战争：双方和平年数清零、年内标记为「卷入战争」（供状态指标判定）。
     * 引擎内部的战争都经 {@link #resolveWar} 自动调用；玩家主动发起的战争由 ChatController 调这个。
     */
    public void noteWar(Civilization first, Civilization second) {
        markWar(first);
        markWar(second);
    }

    private static void markWar(Civilization civilization) {
        if (civilization == null) {
            return;
        }
        civilization.resetPeace();
        civilization.setWarThisYear(true);
    }

    /** 扩张新领土（建新城）：威望 +20。 */
    private static void noteNewCity(Civilization civilization) {
        if (civilization != null) {
            civilization.addPrestige(PRESTIGE_NEW_CITY);
        }
    }

    // ==================== 涌现事件（阈值由代码判定，AI 只写叙事） ====================

    /** 民怨触发内战。 */
    private static final int UNREST_CIVIL_WAR = 60;
    /** 文化水平触发文化繁荣。 */
    private static final int CULTURE_GOLDEN_AGE = 500;
    /** 威望触发伟人诞生。 */
    private static final int PRESTIGE_GREAT_PERSON = 300;
    /** 紧张度触发外交危机。 */
    private static final int TENSION_DIPLOMATIC_CRISIS = 90;
    /** 四种事件的稀有度。 */
    private static final int RARITY_CIVIL_WAR = 90;
    private static final int RARITY_CULTURE_BOOM = 70;
    private static final int RARITY_GREAT_PERSON = 75;
    private static final int RARITY_DIPLOMATIC_CRISIS = 80;
    /** 内战损失的人口比例。 */
    private static final double CIVIL_WAR_POPULATION_LOSS = 0.30;
    /** 内战平息后民怨回落到 30。 */
    private static final int CIVIL_WAR_UNREST_RESET = 30;
    /** 文化繁荣给的科技值。 */
    private static final int CULTURE_BOOM_TECH_GAIN = 50;
    /** 文化繁荣消耗掉的文化水平。 */
    private static final int CULTURE_BOOM_CULTURE_COST = 300;
    /** 伟人诞生消耗掉的威望。 */
    private static final int GREAT_PERSON_PRESTIGE_COST = 200;
    /** 外交危机缓解后紧张度回落量。 */
    private static final int DIPLOMATIC_CRISIS_TENSION_RELIEF = 50;

    /** 四种涌现事件。 */
    private enum EmergentKind {
        CIVIL_WAR, CULTURE_BOOM, GREAT_PERSON, DIPLOMATIC_CRISIS
    }

    /**
     * 每年检查四个阈值（代码判定，不调 AI）：命中就立刻结算效果、写一条 {@link EventType#AI_EVENT}，
     * 并<b>只在触发这一刻</b>请 AI 把这件事写成一句有味道的话（AI 只写叙事，不做判断）。
     */
    private void checkEmergentEvents(int year) {
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            if (civilization.getUnrest() > UNREST_CIVIL_WAR) {
                triggerCivilWar(year, civilization);
            }
            if (civilization.getCulture() > CULTURE_GOLDEN_AGE) {
                triggerCultureBoom(year, civilization);
            }
            if (civilization.getPrestige() > PRESTIGE_GREAT_PERSON) {
                triggerGreatPerson(year, civilization);
            }
            if (civilization.getTension() > TENSION_DIPLOMATIC_CRISIS) {
                triggerDiplomaticCrisis(year, civilization);
            }
        }
    }

    /** 内战：人口 -30%，民怨重置为 30。 */
    private void triggerCivilWar(int year, Civilization civilization) {
        long after = Math.max(1L,
                Math.round(civilization.getPopulation() * (1 - CIVIL_WAR_POPULATION_LOSS)));
        civilization.setPopulation(after);
        civilization.setUnrest(CIVIL_WAR_UNREST_RESET);
        writeEmergentEvent(year, civilization, EmergentKind.CIVIL_WAR,
                "【" + civilization.getName() + "】的农民揭竿而起，国王的军队倒戈，帝国一分为二。");
    }

    /** 文化繁荣：科技 +50，文化水平 -300。 */
    private void triggerCultureBoom(int year, Civilization civilization) {
        civilization.setTechLevel(civilization.getTechLevel() + CULTURE_BOOM_TECH_GAIN);
        civilization.setCulture(civilization.getCulture() - CULTURE_BOOM_CULTURE_COST);
        writeEmergentEvent(year, civilization, EmergentKind.CULTURE_BOOM,
                "【" + civilization.getName() + "】的诗人、哲学家云集，这个时代被后世称为黄金时代。");
    }

    /** 伟人诞生：威望 -500。 */
    private void triggerGreatPerson(int year, Civilization civilization) {
        civilization.setPrestige(civilization.getPrestige() - GREAT_PERSON_PRESTIGE_COST);
        writeEmergentEvent(year, civilization, EmergentKind.GREAT_PERSON,
                "【" + civilization.getName() + "】出了一位天才学者，他的学说将改变世界。");
    }

    /** 外交危机：紧张度 -50。 */
    private void triggerDiplomaticCrisis(int year, Civilization civilization) {
        civilization.setTension(civilization.getTension() - DIPLOMATIC_CRISIS_TENSION_RELIEF);
        writeEmergentEvent(year, civilization, EmergentKind.DIPLOMATIC_CRISIS,
                "【" + civilization.getName() + "】与邻国因边境争端剑拔弩张，一场外交危机就此爆发。");
    }

    /**
     * 把一条涌现事件落进历史：描述优先用 AI 写的那句话，AI 不可用（离线 / 长推进关了叙事）
     * 或调用失败时退回代码里的兜底文案。有 AI 时是异步的，所以直接写 mapper（与百年叙事同一套做法）。
     */
    private void writeEmergentEvent(int year, Civilization civilization, EmergentKind kind,
            String fallback) {
        if (!narrateEnabled || aiNarrator == null || narrativeExecutor == null || worldMapper == null) {
            record(year, EventType.AI_EVENT, fallback, rarityOf(kind));
            return;
        }
        final String prompt = emergentPrompt(kind, civilization);
        final int rarity = rarityOf(kind);
        final int eventYear = year;
        narrativeExecutor.submit(() -> {
            String story = null;
            try {
                story = aiNarrator.narrate(prompt);
            } catch (Exception e) {
                // 限流 / 网络失败：静默退回兜底文案
            }
            if (story == null || story.isBlank()) {
                story = fallback;
            }
            worldMapper.insertEvent(new GameEvent(eventYear, EventType.AI_EVENT, story.trim(), rarity));
        });
    }

    /** 四种涌现事件的稀有度：内战 90 / 文化繁荣 70 / 伟人 75 / 外交危机 80。 */
    private static int rarityOf(EmergentKind kind) {
        return switch (kind) {
            case CIVIL_WAR -> RARITY_CIVIL_WAR;
            case CULTURE_BOOM -> RARITY_CULTURE_BOOM;
            case GREAT_PERSON -> RARITY_GREAT_PERSON;
            case DIPLOMATIC_CRISIS -> RARITY_DIPLOMATIC_CRISIS;
        };
    }

    /** 涌现事件的叙事 prompt（只让 AI 写一句有味道的话）。 */
    private static String emergentPrompt(EmergentKind kind, Civilization civilization) {
        String name = civilization.getName();
        return switch (kind) {
            case CIVIL_WAR -> "星球演化模拟器：文明「" + name + "」民怨沸腾，农民揭竿而起、军队倒戈，"
                    + "帝国一分为二，人口锐减三成。请用一句 30 字左右、有画面感的中文写出这件事，"
                    + "风格像《银河系漫游指南》，只写叙事，不要分析，不要解释。";
            case CULTURE_BOOM -> "星球演化模拟器：文明「" + name + "」文化空前繁荣，诗人与哲学家云集，"
                    + "科技也随之跃进。请用一句 30 字左右、有画面感的中文写出这个黄金时代，"
                    + "风格像《银河系漫游指南》，只写叙事，不要分析，不要解释。";
            case GREAT_PERSON -> "星球演化模拟器：文明「" + name + "」出了一位天才学者，"
                    + "他的学说将改变世界。请用一句 30 字左右、有画面感的中文写出这件事，"
                    + "风格像《银河系漫游指南》，只写叙事，不要分析，不要解释。";
            case DIPLOMATIC_CRISIS -> "星球演化模拟器：文明「" + name + "」与邻国的关系骤然紧张到临界点，"
                    + "一场外交危机爆发。请编一个具体的外交危机（谁和谁、因为什么），"
                    + "用一句 30 字左右的中文写出来，只写叙事，不要分析，不要解释。";
        };
    }

    // ==================== 混乱度（世界对玩家行为的反应） ====================

    /** 混乱度上限。 */
    public static final int CHAOS_MAX = 100;
    /** 玩家单条指令最多带来的混乱度（防止一次爆表）。 */
    private static final int CHAOS_SINGLE_ACTION_CAP = 30;
    /** 阈值：警觉 20 / 恐慌 50 / 崩坏 80。 */
    private static final int CHAOS_TIER_WARN = 20;
    private static final int CHAOS_TIER_PANIC = 50;
    private static final int CHAOS_TIER_COLLAPSE = 80;
    /** 恐慌档（50~80）每年联合围剿的概率。 */
    private static final double CHAOS_COALITION_CHANCE = 0.10;
    /** 崩坏档（80~100）每年天下共讨的概率。 */
    private static final double CHAOS_ALLIANCE_CHANCE = 0.20;
    /** 联合围剿的联军规模。 */
    private static final int CHAOS_COALITION_MIN = 3;
    private static final int CHAOS_COALITION_MAX = 5;
    /** 崩坏档下玩家每块领土每年的叛乱概率。 */
    private static final double CHAOS_REVOLT_CHANCE = 0.05;
    /** 跨阈值事件的稀有度。 */
    private static final int RARITY_CHAOS_THRESHOLD = 70;
    private static final int RARITY_CHAOS_COALITION = 80;
    private static final int RARITY_CHAOS_ALLIANCE = 90;
    /** 连续和平多少年后开始每 10 年 -2。 */
    private static final int CHAOS_PEACE_GRACE_YEARS = 50;

    /** 世界级混乱度（0~100），每局开局重置为 0。 */
    private int chaosLevel;
    /** 本条指令累计的混乱度变化（用于「单次上限 +30」与回传前端提示）。 */
    private int chaosAccumulated;
    private String chaosAccumulatedReason;
    /** 玩家连续无战争的年数（混乱度的和平衰减用）。 */
    private int playerPeaceYears;
    /** 正在联合围剿玩家的国家 id（联合战争状态：AI 决策优先打玩家）。 */
    private final Set<Long> coalitionMembers = new LinkedHashSet<>();

    public int getChaosLevel() {
        return chaosLevel;
    }

    /** 恢复存档用：直接写混乱度（夹在 0~100）。 */
    public void setChaosLevel(int level) {
        chaosLevel = Math.max(0, Math.min(CHAOS_MAX, level));
    }

    /** 一条指令开始：清空本条指令的混乱度累计（单次上限按「整条指令」算）。 */
    public void beginChaos() {
        chaosAccumulated = 0;
        chaosAccumulatedReason = null;
    }

    /**
     * 记一笔玩家行为带来的混乱度（不立刻生效，等 {@link #consumeChaosChange()} 一次性落地）。
     *
     * @param delta  变化量，正 = 恶化（核打击 / 宣战 / 灭国），负 = 缓和（结盟 / 发展科技）
     * @param reason 行为名，前端提示用（如「核打击」「结盟」）
     */
    public void addChaos(int delta, String reason) {
        if (delta == 0) {
            return;
        }
        int applied = delta;
        if (applied > 0) {
            applied = Math.min(applied, CHAOS_SINGLE_ACTION_CAP);
            applied = Math.min(applied, Math.max(0, CHAOS_SINGLE_ACTION_CAP - chaosAccumulated));
        }
        if (applied == 0) {
            if (reason != null && chaosAccumulatedReason == null) {
                chaosAccumulatedReason = reason;
            }
            return;
        }
        chaosAccumulated += applied;
        if (reason != null) {
            chaosAccumulatedReason = reason;
        }
    }

    /**
     * 本条指令结束：把累计变化一次性落到 chaosLevel 上，向上跨过 20/50/80 时各写一条 AI_EVENT，
     * 并返回给前端显示的文本（如「+15（核打击）」）；没有变化返回 null。
     */
    public String consumeChaosChange() {
        if (chaosAccumulated == 0) {
            chaosAccumulatedReason = null;
            return null;
        }
        int delta = chaosAccumulated;
        String reason = chaosAccumulatedReason;
        chaosAccumulated = 0;
        chaosAccumulatedReason = null;
        applyChaos(delta);
        return (delta > 0 ? "+" : "") + delta + "（" + (reason == null ? "未知行为" : reason) + "）";
    }

    /** 真正修改 chaosLevel：夹取，向上跨过阈值时各记一条事件（向下跨不记）。 */
    private void applyChaos(int delta) {
        int before = chaosLevel;
        chaosLevel = Math.max(0, Math.min(CHAOS_MAX, before + delta));
        if (chaosLevel == before) {
            return;
        }
        int year = world.getYear();
        chaosCrossed(year, before, chaosLevel, CHAOS_TIER_WARN, "世界开始注意到【你】的异常行为。");
        chaosCrossed(year, before, chaosLevel, CHAOS_TIER_PANIC, "各国开始恐惧【你】，同盟正在酝酿。");
        chaosCrossed(year, before, chaosLevel, CHAOS_TIER_COLLAPSE, "天下共讨之——所有国家视【你】为威胁。");
    }

    private void chaosCrossed(int year, int before, int after, int threshold, String text) {
        if (before < threshold && after >= threshold) {
            record(year, EventType.AI_EVENT, text, RARITY_CHAOS_THRESHOLD);
        }
    }

    /**
     * 每年结算混乱度的影响（在涌现事件之后）：
     * 连续 {@link #CHAOS_PEACE_GRACE_YEARS} 年无战争每 10 年 -2；警觉档 AI 每年对玩家 -1 关系；
     * 恐慌档 10% 概率 3~5 国围剿；崩坏档 20% 概率天下共讨；&gt;50 玩家民怨每年 +2；&gt;80 领土叛乱。
     */
    private void applyChaosEffects(int year) {
        Civilization player = world.findCivilization(PLAYER_CIV_ID);
        if (player == null || player.getPopulation() <= 0) {
            return;
        }
        // 玩家连续无战争年数：满 50 年后每 10 年 -2（打一年仗就清零重算）
        if (player.isWarThisYear()) {
            playerPeaceYears = 0;
        } else {
            playerPeaceYears++;
        }
        if (playerPeaceYears > CHAOS_PEACE_GRACE_YEARS && playerPeaceYears % 10 == 0) {
            applyChaos(-2);
        }
        int chaos = chaosLevel;

        // 警觉（20~50）：所有 AI 国家每年对玩家 -1 关系
        if (chaos >= CHAOS_TIER_WARN) {
            for (Civilization civilization : world.getCivilizations()) {
                if (civilization.getPopulation() > 0 && civilization.getId() != PLAYER_CIV_ID) {
                    civilization.adjustRelation(PLAYER_CIV_ID, -1);
                }
            }
        }
        // 恐慌（50~80）10% 围剿；崩坏（80~100）20% 天下共讨
        if (chaos >= CHAOS_TIER_COLLAPSE) {
            if (random.nextDouble() < CHAOS_ALLIANCE_CHANCE) {
                launchChaosWar(year, player, null);
            }
        } else if (chaos >= CHAOS_TIER_PANIC) {
            if (random.nextDouble() < CHAOS_COALITION_CHANCE) {
                int size = CHAOS_COALITION_MIN
                        + random.nextInt(CHAOS_COALITION_MAX - CHAOS_COALITION_MIN + 1);
                launchChaosWar(year, player, pickCoalition(player, size));
            }
        }
        // 缓过来了就解散同盟
        if (chaos < CHAOS_TIER_PANIC && !coalitionMembers.isEmpty()) {
            coalitionMembers.clear();
        }
        // 混乱度 > 50：玩家自己的民怨每年 +2
        if (chaos > CHAOS_TIER_PANIC) {
            player.addUnrest(2);
        }
        // 混乱度 > 80：玩家领土每年每块区域 5% 概率叛乱
        if (chaos > CHAOS_TIER_COLLAPSE) {
            revoltRegions(year, player);
        }
    }

    /** 从和玩家接壤的国家里随机挑 size 个组成联军。 */
    private List<Civilization> pickCoalition(Civilization player, int size) {
        List<Civilization> neighbors = borderingCivilizations(player);
        Collections.shuffle(neighbors, random);
        return new ArrayList<>(neighbors.subList(0, Math.min(size, neighbors.size())));
    }

    /**
     * 联合行动：{@code members} 为 null 表示天下共讨（所有 AI 国家一起上）。
     * 写同盟 / 宣战事件、把成员对玩家的关系值直接 -50、标记进入联合战争状态，
     * 然后逐一开战（复用现有战争结算，不改战争逻辑本身）。
     */
    private void launchChaosWar(int year, Civilization player, List<Civilization> members) {
        List<Civilization> coalition = new ArrayList<>();
        if (members == null) {
            for (Civilization civilization : world.getCivilizations()) {
                if (civilization.getPopulation() > 0 && civilization.getId() != PLAYER_CIV_ID) {
                    coalition.add(civilization);
                }
            }
        } else {
            coalition.addAll(members);
        }
        if (coalition.isEmpty()) {
            return;
        }
        boolean allOut = members == null;
        StringBuilder names = new StringBuilder();
        for (Civilization member : coalition) {
            names.append("【").append(member.getName()).append("】");
            member.adjustRelation(PLAYER_CIV_ID, -50);
            coalitionMembers.add(member.getId());
        }
        record(year, EventType.WAR,
                (allOut ? "天下共讨之——" : "") + names
                        + (allOut ? "同时向【你】宣战。" : "结成反【你】同盟，联军压境。"),
                allOut ? RARITY_CHAOS_ALLIANCE : RARITY_CHAOS_COALITION);
        for (Civilization member : coalition) {
            resolveWar(year, member, player);
        }
    }

    /** 崩坏档：玩家领土内每年每块区域 5% 概率叛乱（变回无主）。 */
    private void revoltRegions(int year, Civilization player) {
        List<Integer> lost = new ArrayList<>();
        for (Integer regionId : new ArrayList<>(player.getTerritory())) {
            Region region = world.getRegionMap().get(regionId);
            if (region == null) {
                continue;
            }
            if (random.nextDouble() < CHAOS_REVOLT_CHANCE) {
                region.setOwnerCivId(Region.UNOWNED);
                player.removeRegion(regionId);
                lost.add(regionId);
            }
        }
        if (lost.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Integer regionId : lost) {
            Region region = world.getRegionMap().get(regionId);
            if (region != null) {
                names.append(region.getName()).append('、');
            }
        }
        record(year, EventType.AI_EVENT, "【你】的领土爆发叛乱：" + names + "脱离了你的统治。",
                RARITY_CHAOS_COALITION);
    }

    /**
     * 本轮该让 AI 决策哪些国家：与玩家接壤的国家（最容易和玩家发生互动）+ 最活跃的 N 个
     * （normal 3 个 / high 5 个）。去重后由调用方按 {@link #AI_DECISION_BATCH} 切批。
     */
    private List<Civilization> aiDecisionTargets() {
        Set<Long> picked = new LinkedHashSet<>();
        // 联合战争状态的国家优先进入决策名单（它们正围剿玩家）
        for (Long id : coalitionMembers) {
            Civilization member = findCivilization(id);
            if (member != null && member.getPopulation() > 0) {
                picked.add(id);
            }
        }
        Civilization player = world.findCivilization(PLAYER_CIV_ID);
        if (player != null && player.getPopulation() > 0) {
            for (Civilization neighbor : borderingCivilizations(player)) {
                picked.add(neighbor.getId());
                if (picked.size() >= AI_DECISION_BATCH) {
                    break;
                }
            }
        }
        List<Civilization> byActivity = new ArrayList<>();
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() > 0 && civilization.getId() != PLAYER_CIV_ID) {
                byActivity.add(civilization);
            }
        }
        byActivity.sort((x, y) -> Long.compare(activityScore(y), activityScore(x)));
        for (int i = 0; i < Math.min(topActiveCount(), byActivity.size()); i++) {
            picked.add(byActivity.get(i).getId());
        }
        List<Civilization> result = new ArrayList<>();
        for (Long id : picked) {
            Civilization civilization = findCivilization(id);
            if (civilization != null && civilization.getPopulation() > 0) {
                result.add(civilization);
            }
        }
        return result;
    }

    /** 活跃度：领土越多、资源点越多、人口越多越活跃（用来挑「最活跃的 N 个国家」）。 */
    private static long activityScore(Civilization civilization) {
        return (long) civilization.getTerritory().size() * 1000L
                + civilization.totalPoints()
                + civilization.getPopulation() / 1000L;
    }

    /**
     * 这个时代"该做什么"：喂给 AI 决策的题材提示（按下标分档，与 {@link Civilization#ERAS} 对齐）。
     * 蒙昧 → 农业 → 古典 → 中世纪 → 工业 → 信息。
     */
    static String eraTheme(int eraIndex) {
        if (eraIndex <= 1) {
            return "迁徙、狩猎、发现火";
        }
        if (eraIndex <= 3) {
            return "定居、种地、修渠";
        }
        if (eraIndex <= 5) {
            return "建城、立法、哲学";
        }
        if (eraIndex <= 7) {
            return "封建、宗教、联姻";
        }
        if (eraIndex <= 9) {
            return "殖民、工厂、民族主义";
        }
        return "冷战、科技竞赛、全球化";
    }

    // ==================== 性格影响 ====================

    /** 主动开战的概率倍率：好战 ×3、和平 ×0.3、宗教（对异教敌意）×1.5，其余 ×1。 */
    static double warChanceFactor(Civilization civilization) {
        return switch (civilization.getPersonality() == null ? "" : civilization.getPersonality()) {
            case Civilization.PERSONALITY_WARMONGER -> 3.0;
            case Civilization.PERSONALITY_PACIFIST -> 0.3;
            case Civilization.PERSONALITY_ZEALOT -> 1.5;
            default -> 1.0;
        };
    }

    /** 食物 / 资源产出倍率：商业 +30%。 */
    static double productionFactor(Civilization civilization) {
        return Civilization.PERSONALITY_MERCHANT.equals(civilization.getPersonality()) ? 1.3 : 1.0;
    }

    /** 人口增长倍率：宗教 +20%。 */
    static double populationGrowthFactor(Civilization civilization) {
        return Civilization.PERSONALITY_ZEALOT.equals(civilization.getPersonality()) ? 1.2 : 1.0;
    }

    /** 科技增长倍率：科技至上 ×2（研究倾向 ×2）。 */
    static double techGrowthFactor(Civilization civilization) {
        return Civilization.PERSONALITY_SCIENTIST.equals(civilization.getPersonality()) ? 2.0 : 1.0;
    }

    /** 性格优先点哪条科技分支。 */
    static boolean prefersBranch(String personality, String branch) {
        return switch (personality == null ? "" : personality) {
            case Civilization.PERSONALITY_WARMONGER -> TechTree.BRANCH_MILITARY.equals(branch);
            case Civilization.PERSONALITY_SCIENTIST -> TechTree.BRANCH_SPACE.equals(branch);
            case Civilization.PERSONALITY_MERCHANT, Civilization.PERSONALITY_EXPANSIONIST ->
                    TechTree.BRANCH_ECONOMY.equals(branch);
            case Civilization.PERSONALITY_ZEALOT -> TechTree.BRANCH_SOCIETY.equals(branch);
            case Civilization.PERSONALITY_PACIFIST -> TechTree.BRANCH_ECONOMY.equals(branch)
                    || TechTree.BRANCH_SOCIETY.equals(branch);
            default -> false;
        };
    }

    /** 性格的一句话行为提示（写进 AI prompt）。 */
    static String personalityHint(String personality) {
        return switch (personality == null ? "" : personality) {
            case Civilization.PERSONALITY_WARMONGER -> "好战：倾向主动开战、抢占邻国";
            case Civilization.PERSONALITY_PACIFIST -> "和平：尽量避免开战，倾向结盟与内政";
            case Civilization.PERSONALITY_SCIENTIST -> "科技至上：倾向发展科技与研究";
            case Civilization.PERSONALITY_MERCHANT -> "商业：倾向发展经济与资源";
            case Civilization.PERSONALITY_ZEALOT -> "宗教：人口增长快，对异教文明敌意明显";
            case Civilization.PERSONALITY_EXPANSIONIST -> "扩张：倾向不断占领新领土";
            default -> "均衡";
        };
    }

    // ==================== 国家记忆 ====================

    /** 记忆写进 prompt 时最多带几条。 */
    private static final int MEMORY_IN_PROMPT = 5;
    /** 记忆描述在 prompt 里的最大长度，避免长文撑爆上下文。 */
    private static final int MEMORY_TEXT_LIMIT = 40;

    /**
     * 记一件事：写进该文明的记忆（超过上限丢最旧的），并按 sentiment × 10 折算成
     * 对目标文明的关系值变化——这套折算在 {@link Civilization#remember} 里完成。
     */
    private void remember(Civilization civilization, int year, String type, Civilization target,
            String description, double sentiment) {
        if (civilization == null) {
            return;
        }
        double clamped = Math.max(-1.0, Math.min(1.0, sentiment));
        civilization.remember(new Memory(year, type,
                target == null ? 0 : (int) target.getId(),
                description == null ? "" : description, clamped));
    }

    // ==================== 国家目标（每 50 / 200 年更新） ====================

    /**
     * 每年调用：先推进完成度（满了就结算奖励），再在 50 年整点（长期目标在 200 年整点）
     * 分批请 AI 给各国更新目标。分批是硬要求——免费模型一次输出不了太多，每批最多
     * {@link #GOALS_PER_BATCH} 个国家；队列满或 AI 失败就静默跳过，下一轮再来。
     */
    private void tickGoals(int year) {
        advanceGoalProgress(year);
        if (year <= 0 || year % GOAL_UPDATE_INTERVAL != 0) {
            return;
        }
        if (!narrateEnabled || aiNarrator == null || narrativeExecutor == null || objectMapper == null) {
            return;
        }
        boolean refreshLong = year % LONG_GOAL_UPDATE_INTERVAL == 0;
        List<Civilization> targets = new ArrayList<>();
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() > 0 && civilization.getId() != PLAYER_CIV_ID) {
                targets.add(civilization);
            }
        }
        for (int i = 0; i < targets.size(); i += GOALS_PER_BATCH) {
            List<Civilization> batch = new ArrayList<>(
                    targets.subList(i, Math.min(targets.size(), i + GOALS_PER_BATCH)));
            final String prompt = buildGoalPrompt(batch, refreshLong);
            final boolean withLong = refreshLong;
            narrativeExecutor.submit(() -> {
                try {
                    String reply = aiNarrator.narrate(prompt);
                    if (reply != null) {
                        pendingGoals.add(new GoalReply(withLong, reply));
                    }
                } catch (Exception e) {
                    // 静默跳过：免费模型限流时不要刷屏，下一轮再来
                }
            });
        }
    }

    /** 每年推进一点短期目标完成度；满 100 就结算（写事件 + 奖励科技点），并清掉旧目标等下一轮重定。 */
    private void advanceGoalProgress(int year) {
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0
                    || civilization.getShortTermGoal() == null
                    || civilization.getShortTermGoal().isBlank()) {
                continue;
            }
            int next = civilization.getGoalProgress() + 1 + random.nextInt(3);
            if (next >= Civilization.GOAL_PROGRESS_MAX) {
                civilization.setGoalProgress(0);
                civilization.addPoints(Civilization.POINT_SCIENCE, GOAL_REWARD_SCIENCE_POINTS);
                record(year, EventType.AI_EVENT,
                        civilization.getName() + "达成了目标「" + civilization.getShortTermGoal()
                                + "」（科学点 +" + GOAL_REWARD_SCIENCE_POINTS + "）", AI_EVENT_RARITY);
                // 目标已达成：清空，等下一个 50 年周期的 AI 重定
                civilization.setShortTermGoal("");
            } else {
                civilization.setGoalProgress(next);
            }
        }
    }

    /** 拼「定目标」prompt：本批国家（状态 / 性格 / 记忆 / 关系）+ 输出格式。 */
    private String buildGoalPrompt(List<Civilization> batch, boolean refreshLong) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器的世界导演。给下面每个文明定一个当前目标。\n")
                .append("只输出 JSON，不要解释，不要代码块。格式：\n")
                .append("{\"goals\":[{\"civId\":2,\"shortTermGoal\":\"8~16字的目标\"");
        if (refreshLong) {
            prompt.append(",\"longTermGoal\":\"8~16字的长期方向\"");
        }
        prompt.append("}]}\n")
                .append("- shortTermGoal：未来 50 年要做的一件事\n");
        if (refreshLong) {
            prompt.append("- longTermGoal：未来 200 年的长期方向\n");
        }
        prompt.append("- 目标要贴合该文明的性格、最近的记忆与对外关系\n")
                .append("- civId 只能用下面列出的 id，本批最多 ").append(GOALS_PER_BATCH).append(" 个\n\n");
        prompt.append("世界状态：\n");
        prompt.append("- 当前年份：").append(world.getYear()).append('\n');
        prompt.append("- 当前时代：").append(ERAS[currentEraIndex]).append('\n');
        for (Civilization civilization : batch) {
            prompt.append("- id=").append(civilization.getId())
                    .append("，名称=").append(civilization.getName())
                    .append("，性格=").append(civilization.personalityLabel())
                    .append("，人口=").append(civilization.getPopulation())
                    .append("，科技=").append(civilization.getTechLevel())
                    .append("，领土=").append(civilization.getTerritory().size()).append("块")
                    .append("，四柱（科技/民生/资源/军力）=").append(civilization.getScience()).append('/')
                    .append(civilization.getWelfare()).append('/')
                    .append(civilization.getResource()).append('/')
                    .append(civilization.getMilitary()).append('\n');
            if (!civilization.getLongTermGoal().isBlank()) {
                prompt.append("    当前长期目标：").append(civilization.getLongTermGoal()).append('\n');
            }
            // 记得谁跟谁有过节：关系值 + 最近记忆
            StringBuilder relations = new StringBuilder();
            civilization.getRelations().forEach((id, value) ->
                    relations.append("id=").append(id).append(':').append(value).append(' '));
            prompt.append("    对外关系：").append(relations.length() == 0 ? "（无）" : relations.toString().trim())
                    .append('\n');
            for (Memory memory : civilization.recentMemories(MEMORY_IN_PROMPT)) {
                prompt.append("    记忆[").append(memory.year()).append("年 ").append(memory.type())
                        .append(" → id=").append(memory.targetCivId()).append("] ")
                        .append(truncate(memory.description(), MEMORY_TEXT_LIMIT)).append('\n');
            }
        }
        return prompt.toString();
    }

    /** 落地所有已到达的目标回复；只在 tick 线程调用，单条出错不影响其他条目。 */
    private void applyPendingGoals() {
        GoalReply reply;
        while ((reply = pendingGoals.poll()) != null) {
            try {
                applyGoalReply(reply);
            } catch (Exception e) {
                System.out.println("[国家目标] 回复无法处理，已跳过：" + e.getMessage());
            }
        }
    }

    /** 一条目标回复：逐个文明写回目标，一批最多处理 {@link #GOALS_PER_BATCH} 个。 */
    private void applyGoalReply(GoalReply item) throws Exception {
        JsonNode root = parseJsonObject(item.reply());
        JsonNode goals = root.path("goals");
        if (!goals.isArray()) {
            return;
        }
        int applied = 0;
        for (JsonNode node : goals) {
            if (applied >= GOALS_PER_BATCH) {
                break;   // 一批最多 5 个，多余的直接丢弃
            }
            Civilization civilization = findCivilization(node.path("civId").asLong(-1));
            if (civilization == null || civilization.getId() == PLAYER_CIV_ID
                    || civilization.getPopulation() <= 0) {
                continue;
            }
            String shortGoal = node.path("shortTermGoal").asText("").trim();
            String longGoal = node.path("longTermGoal").asText("").trim();
            if (!shortGoal.isEmpty()) {
                civilization.setShortTermGoal(shortGoal);
                civilization.setGoalProgress(0);
            }
            if (item.refreshLong() && !longGoal.isEmpty()) {
                civilization.setLongTermGoal(longGoal);
            }
            applied++;
        }
        if (applied > 0) {
            System.out.println("[国家目标] 已更新 " + applied + " 个文明的目标");
        }
    }

    // ==================== 事件背景（本地数据拼「为什么」，不调 AI） ====================

    /** 原因描述在事件文案里的最大长度。 */
    private static final int REASON_TEXT_LIMIT = 30;

    /**
     * 挑出该文明「针对目标」最相关的一条记忆：优先最近的、指向该目标的记忆；
     * 没有就退回最近的一条记忆；一条都没有返回 null。
     */
    private Memory mostRelevantMemory(Civilization civilization, Civilization target) {
        if (civilization == null) {
            return null;
        }
        List<Memory> all = civilization.getMemories();
        if (target != null) {
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).targetCivId() == target.getId()) {
                    return all.get(i);
                }
            }
        }
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /**
     * 事件「为什么」：性格 + 对目标的关系值 + 一条历史原因（从记忆里找）。
     * 形如「好战，对文明-5关系-30，因1200年我们进攻文明-5」。
     */
    private String backgroundOf(Civilization actor, Civilization target) {
        if (actor == null) {
            return "不明";
        }
        StringBuilder sb = new StringBuilder(actor.personalityLabel());
        if (target != null) {
            sb.append("，对").append(target.getName()).append("关系")
                    .append(actor.relationTo(target.getId()));
            Memory cause = mostRelevantMemory(actor, target);
            if (cause != null && !cause.description().isBlank()) {
                sb.append("，因").append(cause.year()).append("年")
                        .append(truncate(cause.description(), REASON_TEXT_LIMIT));
            }
        }
        return sb.toString();
    }

    /** 关系最差的其他文明（用于「共同对抗【第三方】」）；没有别人时返回 null。 */
    private Civilization worstRivalOf(Civilization civilization, Civilization excludeA, Civilization excludeB) {
        Civilization worst = null;
        int lowest = Integer.MAX_VALUE;
        for (Civilization other : world.getCivilizations()) {
            if (other.getPopulation() <= 0 || other.getId() == civilization.getId()) {
                continue;
            }
            if (excludeA != null && other.getId() == excludeA.getId()) {
                continue;
            }
            if (excludeB != null && other.getId() == excludeB.getId()) {
                continue;
            }
            int relation = civilization.relationTo(other.getId());
            if (relation < lowest) {
                lowest = relation;
                worst = other;
            }
        }
        return worst;
    }

    /** 内政方向：有短期目标就用它，否则按最低的那一柱给一个方向。 */
    static String goalDirection(Civilization civilization) {
        if (civilization.getShortTermGoal() != null && !civilization.getShortTermGoal().isBlank()) {
            return civilization.getShortTermGoal();
        }
        int science = civilization.getScience();
        int welfare = civilization.getWelfare();
        int resource = civilization.getResource();
        int military = civilization.getMilitary();
        int lowest = Math.min(Math.min(science, welfare), Math.min(resource, military));
        if (lowest == science) {
            return "科技";
        }
        if (lowest == welfare) {
            return "民生";
        }
        if (lowest == resource) {
            return "资源";
        }
        return "军备";
    }

    /** 截断过长文本（prompt 省 token 用）。 */
    static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    /** 与该文明领土接壤的其他文明（按区域邻接表算，只算存活文明，不含自己）。 */
    private List<Civilization> borderingCivilizations(Civilization civilization) {
        List<Civilization> result = new ArrayList<>();
        if (civilization == null) {
            return result;
        }
        Set<Long> owners = new LinkedHashSet<>();
        for (Integer regionId : civilization.getTerritory()) {
            Region region = world.getRegionMap().get(regionId);
            if (region == null) {
                continue;
            }
            for (Integer neighborId : region.getNeighbors()) {
                Region neighbor = world.getRegionMap().get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                int owner = neighbor.getOwnerCivId();
                if (owner > 0 && owner != civilization.getId()) {
                    owners.add((long) owner);
                }
            }
        }
        for (Long ownerId : owners) {
            Civilization owner = findCivilization(ownerId);
            if (owner != null && owner.getPopulation() > 0) {
                result.add(owner);
            }
        }
        return result;
    }

    /** 内政告急线：任一柱低于它就必须优先修复，不许扩张 / 开战。 */
    private static final int PILLAR_CRISIS = 20;

    /** 修复内政时，最低那一柱的回升量。 */
    private static final int REPAIR_GAIN = 15;
    /** 修复内政的代价：其余三柱的回落量。 */
    private static final int REPAIR_SPILL = 1;

    /** 该文明是否有一柱低于告急线。 */
    private static boolean needsRepair(Civilization civilization) {
        return Math.min(Math.min(civilization.getScience(), civilization.getWelfare()),
                Math.min(civilization.getResource(), civilization.getMilitary())) < PILLAR_CRISIS;
    }

    /** 四柱整体位移（setter 自带 0~100 夹取）。 */
    private static void bumpPillars(Civilization civilization, int science, int welfare, int resource,
            int military) {
        civilization.setScience(civilization.getScience() + science);
        civilization.setWelfare(civilization.getWelfare() + welfare);
        civilization.setResource(civilization.getResource() + resource);
        civilization.setMilitary(civilization.getMilitary() + military);
    }

    /**
     * 拼 prompt：本批文明 + 最近 20 年事件 + 当前时代，一次要回世界事件和这批文明各自的决策。
     * 只列本批国家（最多 {@link #AI_DECISION_BATCH} 个），避免一次输出过长被截断。
     */
    private String buildAiTickPrompt(List<Civilization> batch) {
        int year = world.getYear();
        int interval = aiTickInterval();
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器的世界导演。根据下面的世界状态，一次性输出两部分内容：\n")
                .append("1) worldEvents：接下来 ").append(interval).append(" 年发生的 1~3 条世界事件（天灾、发现、思潮、战争等）\n")
                .append("2) civDecisions：下面列出的每个文明在这 ").append(interval).append(" 年里的一个行动\n")
                .append("只输出 JSON，不要解释，不要代码块。格式：\n")
                .append("{\"worldEvents\":[{\"name\":\"事件名\",\"description\":\"发生了什么\",")
                .append("\"affectedCivIds\":[2,3],\"effects\":{\"tech\":50,\"population\":-1000,\"resources\":2000}}],")
                .append("\"civDecisions\":[{\"civId\":2,\"action\":\"develop\",\"decision\":\"发展科技\",\"effects\":{\"tech\":50}},")
                .append("{\"civId\":3,\"action\":\"attack\",\"targetCivId\":5,\"decision\":\"攻打文明-5\"},")
                .append("{\"civId\":4,\"action\":\"expand\",\"decision\":\"扩张\"}]}\n")
                .append("- effects.tech=科技增减、effects.population=人口增减（作用于 affectedCivIds 里每个文明），")
                .append("effects.resources=全球资源增减；都可以为负，也可以省略\n")
                .append("- civDecisions 的 action 只能从这 8 个里选：\n")
                .append("  attack=攻打某国（必须给 targetCivId）、ally=与某国结盟（给 targetCivId）、")
                .append("betray=背叛某国（给 targetCivId）、research=研究科技、expand=占领接壤的无主区域、")
                .append("develop=发展科技、migrate=迁徙、nothing=按兵不动\n")
                .append("- 只给下面列出的 id 写 civDecisions，最多 ").append(AI_DECISION_BATCH).append(" 个，不要编造 id\n")
                .append("- 所有事件描述都要解释「为什么」：结合该文明的性格、对目标的关系值、")
                .append("记忆里的历史原因、以及当前时代。按下面的模板写：\n")
                .append("  · 战争：「【A】（性格）因【原因】，向【B】宣战。」\n")
                .append("  · 结盟：「【A】与【B】缔结同盟，共同对抗【第三方】。」\n")
                .append("  · 内政：「【A】专注发展【方向】。」\n")
                .append("  · 科技：「【A】的学者发明了【XXX】，举国欢庆。」\n")
                .append("- 某个文明只要有任何一柱低于 20，就必须选\"修复内政\"，不许扩张、不许开战\n")
                .append("- 每个文明后面标了它的性格，决策必须符合性格：好战爱开战、和平爱结盟、")
                .append("科技至上爱发展科技、商业爱发展经济、宗教人口多且敌视异教、扩张爱占地\n")
                .append("- 玩家文明（id=").append(PLAYER_CIV_ID).append("）不参与决策，不要给它写 civDecisions\n")
                .append("- affectedCivIds 和 targetCivId 只能用下面列出的存活文明 id，不要编造\n\n");
        prompt.append("世界状态：\n");
        prompt.append("- 当前年份：").append(year).append('\n');
        prompt.append("- 当前时代：").append(ERAS[currentEraIndex])
                .append("（这个时代该做什么：").append(eraTheme(currentEraIndex)).append("）\n");
        prompt.append("- 全球资源存量：").append(Math.round(resources)).append('\n');
        prompt.append("- 本轮需要决策的文明（就这些，别的不写）：\n");
        for (Civilization civilization : batch) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            prompt.append("  id=").append(civilization.getId())
                    .append("，名称=").append(civilization.getName())
                    .append("，性格=").append(civilization.personalityLabel())
                    .append("（").append(personalityHint(civilization.getPersonality())).append("）")
                    .append("，人口=").append(civilization.getPopulation())
                    .append("，科技=").append(civilization.getTechLevel())
                    .append("，领土=").append(civilization.getTerritory().size()).append("块")
                    .append("，四柱（科技/民生/资源/军力）=").append(civilization.getScience()).append('/')
                    .append(civilization.getWelfare()).append('/')
                    .append(civilization.getResource()).append('/')
                    .append(civilization.getMilitary())
                    .append(civilization.getId() == PLAYER_CIV_ID ? "（玩家，不参与决策）" : "")
                    .append('\n');
            // 最近 5 条记忆：让 AI 知道它跟谁有过节、跟谁有交情
            for (Memory memory : civilization.recentMemories(MEMORY_IN_PROMPT)) {
                prompt.append("      记忆[").append(memory.year()).append("年 ").append(memory.type())
                        .append(" → id=").append(memory.targetCivId()).append("] ")
                        .append(truncate(memory.description(), MEMORY_TEXT_LIMIT)).append('\n');
            }
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
        // 关键事件后让 AI 判断是否留下因果种子（取首个受影响文明作为种植者）
        int plantedBy = 0;
        if (affected.isArray()) {
            for (JsonNode idNode : affected) {
                Civilization civ = findCivilization(idNode.asLong(-1));
                if (civ != null && civ.getPopulation() > 0) {
                    plantedBy = (int) civ.getId();
                    break;
                }
            }
        }
        considerSeed(plantedBy, "世界事件：" + text);
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
        String action = decision.path("action").asText("").trim().toLowerCase();
        JsonNode effects = decision.path("effects");
        // 支持英文 action：折算成等价的中文关键词，后面的分支逻辑完全复用
        // （attack 保留原文本，好让 targetCivId 缺失时还能从「攻打文明-5」里反查目标）
        text = switch (action) {
            case "attack" -> text.contains("攻打") || text.contains("进攻") ? text : "攻打" + text;
            case "ally" -> "结盟";
            case "betray" -> "背叛";
            case "research" -> "研究";
            case "expand" -> "扩张";
            case "develop" -> "发展科技";
            case "migrate" -> "迁徙";
            case "nothing" -> "按兵不动";
            default -> text;
        };
        boolean aggressive = text.contains("攻打") || text.contains("进攻")
                || text.contains("扩张") || text.contains("占领");
        // 内政告急：任一柱低于告急线时，强制改成修复内政——不扩张、不开战
        if (aggressive && needsRepair(civilization)) {
            repairPillar(civilization);
            record(year, EventType.AI_EVENT,
                    "【" + civilization.getName() + "】内政告急，搁置"
                            + (text.contains("扩张") ? "扩张" : "战事") + "，专注发展【"
                            + goalDirection(civilization) + "】（" + civilization.personalityLabel() + "）。",
                    AI_EVENT_RARITY);
            return;
        }
        if (text.contains("修复") || text.contains("内政")) {
            repairPillar(civilization);
            // 模板：【A】专注发展【方向】。
            record(year, EventType.AI_EVENT,
                    "【" + civilization.getName() + "】专注发展【" + goalDirection(civilization)
                            + "】（" + civilization.personalityLabel() + "）。",
                    AI_EVENT_RARITY);
            return;
        }
        if (text.contains("结盟") || text.contains("同盟")) {
            Civilization ally = resolveDecisionTarget(civilization, decision);
            if (ally == null) {
                record(year, EventType.AI_EVENT, civilization.getName() + "想结盟，但没找到对象", AI_EVENT_RARITY);
                return;
            }
            // 结盟：双方各记一笔正面记忆（关系 +7）
            remember(civilization, year, "结盟", ally, "与" + ally.getName() + "结盟", 0.7);
            remember(ally, year, "结盟", civilization, "与" + civilization.getName() + "结盟", 0.7);
            // 模板：【A】与【B】缔结同盟，共同对抗【第三方】。（找不出第三方就省略后半句）
            Civilization rival = worstRivalOf(civilization, ally, null);
            record(year, EventType.AI_EVENT,
                    "【" + civilization.getName() + "】与【" + ally.getName() + "】缔结同盟"
                            + (rival == null ? "。" : "，共同对抗【" + rival.getName() + "】。"),
                    AI_EVENT_RARITY);
            return;
        }
        if (text.contains("背叛") || text.contains("毁约")) {
            Civilization victim = resolveDecisionTarget(civilization, decision);
            if (victim == null) {
                record(year, EventType.AI_EVENT, civilization.getName() + "想背叛谁，但没找到对象", AI_EVENT_RARITY);
                return;
            }
            // 背叛：被背叛方记强负（关系 -10），背叛方自己也留一笔较轻的负记忆
            remember(victim, year, "背叛", civilization, civilization.getName() + "背叛了我们", -1.0);
            remember(civilization, year, "背叛", victim, "我们背弃了" + victim.getName(), -0.5);
            record(year, EventType.AI_EVENT,
                    civilization.getName() + "背弃了与" + victim.getName() + "的约定", AI_EVENT_RARITY);
            return;
        }
        if (text.contains("攻打") || text.contains("进攻")) {
            aiAttack(year, civilization, decision);
            return;
        }
        if (text.contains("扩张") || text.contains("占领")) {
            // 扩张耗民生、得资源
            bumpPillars(civilization, 0, -4, 4, 0);
            record(year, EventType.AI_EVENT,
                    "【" + civilization.getName() + "】向外扩张" + expandOne(civilization)
                            + "（" + civilization.personalityLabel() + "）。",
                    AI_EVENT_RARITY);
            return;
        }
        // 研究：科技点够就挑一个能解锁的节点（pickResearch 已自带「点得起」过滤）
        if (text.contains("研究")) {
            TechNode node = pickResearch(civilization);
            if (node == null) {
                record(year, EventType.AI_EVENT,
                        civilization.getName() + "在积累科技点，暂时没有研究得起的项目", AI_EVENT_RARITY);
            } else {
                unlockNode(year, civilization, node);
            }
            return;
        }
        // 迁徙：耗资源换民生，顺带尝试占一块接壤的无主区域
        if (text.contains("迁徙")) {
            bumpPillars(civilization, 0, 2, -2, 0);
            record(year, EventType.AI_EVENT,
                    "【" + civilization.getName() + "】举族迁徙" + expandOne(civilization)
                            + "（" + civilization.personalityLabel() + "）。",
                    AI_EVENT_RARITY);
            return;
        }
        // 按兵不动：不写事件，避免历史列表被「什么都没发生」刷屏
        if (text.contains("按兵不动") || text.contains("观望")) {
            return;
        }
        if (text.contains("科技")) {
            int gain = (int) clamp(effects.path("tech").asLong(AI_DECISION_TECH_GAIN),
                    -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
            applyEffects(year, civilization, 0, gain);
            // 发展科技：涨科技柱、耗资源柱
            bumpPillars(civilization, 6, 0, -4, 0);
            record(year, EventType.AI_EVENT,
                    "【" + civilization.getName() + "】专注发展【科技】（" + civilization.personalityLabel()
                            + "，科技 +" + gain + "）。",
                    AI_EVENT_RARITY);
            return;
        }
        applyEffects(year, civilization,
                clamp(effects.path("population").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT),
                (int) clamp(effects.path("tech").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT));
        record(year, EventType.AI_EVENT, civilization.getName() + "的决策：" + (text.isEmpty() ? "调整" : text),
                AI_EVENT_RARITY);
    }

    /** 从一条决策里解析目标文明：先看 targetCivId，再退回从决策文本里找文明名；无效返回 null。 */
    private Civilization resolveDecisionTarget(Civilization actor, JsonNode decision) {
        Civilization target = findCivilization(decision.path("targetCivId").asLong(-1));
        if (target == null) {
            target = findByDecisionText(decision.path("decision").asText(""));
        }
        if (target == null || target.getId() == actor.getId() || target.getPopulation() <= 0) {
            return null;
        }
        return target;
    }

    /** 修复内政：把最低的那一柱抬上来，其余柱轻微回落（资源总是有限的）。 */
    private static void repairPillar(Civilization civilization) {
        int science = civilization.getScience();
        int welfare = civilization.getWelfare();
        int resource = civilization.getResource();
        int military = civilization.getMilitary();
        int lowest = Math.min(Math.min(science, welfare), Math.min(resource, military));
        bumpPillars(civilization,
                science == lowest ? REPAIR_GAIN : -REPAIR_SPILL,
                welfare == lowest ? REPAIR_GAIN : -REPAIR_SPILL,
                resource == lowest ? REPAIR_GAIN : -REPAIR_SPILL,
                military == lowest ? REPAIR_GAIN : -REPAIR_SPILL);
    }

    /** 给单个文明套科技/人口增减；人口跌破灭绝线按灭亡处理（与规则事件同口径）。 */
    private void applyEffects(int year, Civilization civilization, long populationDelta, int techDelta) {
        if (techDelta != 0) {
            int before = civilization.getTechLevel();
            int after = (int) clamp(before + (long) techDelta, 0, AI_EFFECT_LIMIT);
            if (after > before) {
                // 科技增长同时涨科技点
                civilization.gainTech(after - before);
            } else if (after < before) {
                civilization.setTechLevel(after);
            }
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
        String detail = resolveWar(year, attacker, target);
        int jitter = random.nextInt(MajorEventRules.WAR_RARITY_JITTER + 1);
        // 带背景：谁、什么性格、跟对方什么关系、为什么（来自记忆）
        record(year, EventType.WAR,
                "【" + attacker.getName() + "】（" + backgroundOf(attacker, target) + "）向【"
                        + target.getName() + "】宣战（" + detail + "）。",
                MajorEventRules.warRarity(totalTech(), jitter));
        // 战争是典型的长期仇恨来源，让 AI 判断是否种下种子
        considerSeed((int) attacker.getId(),
                "战争：" + attacker.getName() + "攻打" + target.getName() + "（目标 id=" + target.getId() + "）");
    }

    /** 文明实力：人口 × 科技系数（科技每 1000 点折算一成实力，避免纯人口碾压）。 */
    private static double powerOf(Civilization civilization) {
        return Math.max(1L, civilization.getPopulation()) * (1.0 + civilization.getTechLevel() / 1000.0);
    }

    /**
     * AI 之间的战争结算：按双方实力比（人口 × 科技系数）分档，而不是双方各掉一个固定百分比。
     *
     * <ul>
     *   <li>攻方实力 ≥ 守方 1.5 倍：攻方损失 5%~15%，守方损失 40%~70%（碾压）</li>
     *   <li>0.7 ~ 1.5 倍：势均力敌，双方各损失 20%~35%</li>
     *   <li>攻方 ≤ 守方 0.7 倍：攻方损失 40%~70%，守方损失 5%~15%（攻方吃亏）</li>
     * </ul>
     *
     * @return 描述双方损失与态势的文字，供事件文案使用
     */
    private String resolveWar(int year, Civilization attacker, Civilization defender) {
        double ratio = powerOf(attacker) / powerOf(defender);
        double attackerLossRate;
        double defenderLossRate;
        String balance;
        if (ratio >= 1.5) {
            attackerLossRate = 0.05 + random.nextDouble() * 0.10;
            defenderLossRate = 0.40 + random.nextDouble() * 0.30;
            balance = "攻方占优";
        } else if (ratio >= 0.7) {
            attackerLossRate = 0.20 + random.nextDouble() * 0.15;
            defenderLossRate = 0.20 + random.nextDouble() * 0.15;
            balance = "势均力敌";
        } else {
            attackerLossRate = 0.40 + random.nextDouble() * 0.30;
            defenderLossRate = 0.05 + random.nextDouble() * 0.10;
            balance = "攻方吃亏";
        }
        // 战争标记：双方和平年数清零（状态指标据此加民怨 / 紧张度、扣文化）
        noteWar(attacker, defender);
        // 威望：损失更小的一方算「打赢了」
        if (attackerLossRate <= defenderLossRate) {
            attacker.addPrestige(PRESTIGE_WIN_WAR);
        } else {
            defender.addPrestige(PRESTIGE_WIN_WAR);
        }
        applyWarLoss(year, attacker, Math.round(attacker.getPopulation() * attackerLossRate));
        applyWarLoss(year, defender, Math.round(defender.getPopulation() * defenderLossRate));
        resources = Math.max(0, resources * 0.85);
        // 战争同样重创两国的军力与民生
        bumpPillars(attacker, 0, -6, 0, -8);
        bumpPillars(defender, 0, -6, 0, -8);
        // 记忆：双方各记一笔（守方是"被攻击"，情绪更负面），关系值随之下降
        remember(defender, year, "被攻击", attacker, attacker.getName() + "进攻我们", -0.8);
        remember(attacker, year, "开战", defender, "我们进攻" + defender.getName(), -0.3);
        if (defender.getPopulation() <= 0) {
            // 被灭国：威望 -50
            defender.addPrestige(-PRESTIGE_LOSE_NATION);
            // 灭国：灭它的一方记中性（那是它的战果），邻国记负（畏惧并敌视这个新霸主）
            remember(attacker, year, "灭国", defender, "我们灭了" + defender.getName(), 0.0);
            for (Civilization neighbor : borderingCivilizations(defender)) {
                if (neighbor.getId() == attacker.getId()) {
                    continue;
                }
                remember(neighbor, year, "灭国", attacker,
                        attacker.getName() + "灭了邻国" + defender.getName(), -0.6);
            }
        }
        return balance + "：攻方损失约 " + Math.round(attackerLossRate * 100)
                + "% 人口，守方损失约 " + Math.round(defenderLossRate * 100) + "% 人口";
    }

    /** 占一块与该文明接壤的无主区域，返回拼事件描述用的后缀。 */
    private String expandOne(Civilization civilization) {
        for (Region region : world.getRegionMap().all()) {
            if (region.isUnowned() && borders(civilization, region)) {
                region.setOwnerCivId((int) civilization.getId());
                civilization.addRegion(region.getId());
                // 建新城：威望 +20
                noteNewCity(civilization);
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

    // ==================== 因果种子（种下 → 发芽） ====================

    /** 当前所有因果种子（只读副本，供存档 / 调试）。 */
    public List<Seed> getSeeds() {
        synchronized (seeds) {
            return new ArrayList<>(seeds);
        }
    }

    /** 恢复存档用：整表替换种子列表（超过容量上限时保留最新的那批）。 */
    public void replaceSeeds(List<Seed> restored) {
        synchronized (seeds) {
            seeds.clear();
            if (restored != null) {
                for (Seed seed : restored) {
                    if (seed != null) {
                        seeds.add(seed);
                    }
                }
            }
            while (seeds.size() > MAX_SEEDS_TOTAL) {
                seeds.remove(0);
            }
        }
    }

    /**
     * 种下一颗种子。稀有度低于重大阈值（{@link #SEED_MIN_RARITY}）的一律不种。
     * 列表满了（{@link #MAX_SEEDS_TOTAL}）就丢掉最旧的一颗，给新种子腾位置。
     *
     * @return true 表示已种下；false 表示被稀有度门槛挡下
     */
    public boolean plantSeed(Seed seed) {
        if (seed == null || seed.rarity() < SEED_MIN_RARITY) {
            return false;
        }
        synchronized (seeds) {
            while (seeds.size() >= MAX_SEEDS_TOTAL) {
                seeds.remove(0);   // 列表按种下顺序排列，第 0 个就是最旧的
            }
            seeds.add(seed);
        }
        return true;
    }

    /**
     * 关键事件后请 AI 判断是否留下因果种子：AI 认为有长期后果才种。
     * 走异步执行器（与 AI 叙事同一套），不阻塞 tick；AI 回复里 rarity &lt; 60 的种子会被丢弃。
     *
     * @param plantedBy   种下它的文明 id（玩家文明为 1；世界事件取首个受影响文明，可能为 0）
     * @param contextText 刚刚发生的关键事件描述
     */
    public void considerSeed(int plantedBy, String contextText) {
        if (!narrateEnabled || contextText == null || contextText.isBlank()) {
            return;
        }
        // 直接 new 构造的引擎（离线测试）没有注入，跳过
        if (aiNarrator == null || narrativeExecutor == null || objectMapper == null) {
            return;
        }
        final int actor = plantedBy;
        final String prompt = buildSeedPrompt(actor, contextText);
        narrativeExecutor.submit(() -> {
            try {
                String reply = aiNarrator.narrate(prompt);
                if (reply == null) {
                    return;
                }
                Seed seed = parseSeed(reply, actor);
                if (seed != null && plantSeed(seed)) {
                    System.out.println("[因果种子] 种下「" + seed.type() + "」（稀有度 " + seed.rarity()
                            + "，" + seed.triggerAfterYears() + " 年后可能发芽）：" + seed.note());
                }
            } catch (Exception e) {
                System.out.println("[因果种子] 判断回复无法处理，已跳过：" + e.getMessage());
            }
        });
    }

    /** 拼「判断种子」prompt：事件 + 世界状态 + 稀有度标准 + 输出格式。 */
    private String buildSeedPrompt(int plantedBy, String contextText) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器的史官。判断下面这件刚发生的事会不会留下长期后果（因果种子）。\n")
                .append("如果有长期后果，只输出 JSON：\n")
                .append("{\"seed\":{\"type\":\"灭国之仇\",\"targetCivId\":5,\"rarity\":85,")
                .append("\"note\":\"文明-5 被灭，它的遗民不会忘记\"}}\n")
                .append("如果只是日常小事、没有长期后果，输出：{\"seed\":null}\n")
                .append("不要解释，不要代码块。\n")
                .append("type 只能从这四种里选：\"灭国之仇\"、\"联姻纽带\"、\"背叛记忆\"、\"援助之恩\"。\n")
                .append("rarity（0~100）怎么定：\n")
                .append("- 灭国、核打击、大屠杀 → 80~100\n")
                .append("- 侵略、背叛、毁约 → 60~79\n")
                .append("- 联姻、结盟、援助 → 60~79（善意的种子）\n")
                .append("- 日常发展、贸易、小事 → 0~59（不种，直接输出 seed:null 或给出低稀有度）\n")
                .append("targetCivId 用下面列出的存活文明 id，没有特定目标就填 0，不要编造。\n")
                .append("不用给发芽年限，系统会按 rarity 自动决定（60~79 约 100~200 年，80~100 约 200~400 年）。\n\n");
        prompt.append("种植者文明 id：").append(plantedBy).append('\n');
        prompt.append("刚发生的关键事件：").append(contextText).append('\n');
        prompt.append("当前世界状态：\n").append(describeWorldForSeed());
        return prompt.toString();
    }

    /** 解析 AI 的种子 JSON：没有 seed 或字段不全返回 null。 */
    private Seed parseSeed(String reply, int plantedBy) throws Exception {
        JsonNode root = parseJsonObject(reply);
        JsonNode node = root.path("seed");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String type = node.path("type").asText("").trim();
        if (type.isEmpty()) {
            return null;
        }
        int targetCivId = node.path("targetCivId").asInt(0);
        int rarity = (int) clamp(node.path("rarity").asInt(0), 0, 100);
        // 发芽年限不再听 AI 的，按稀有度随机：仇恨越深，酝酿越久
        int triggerAfterYears = rollTriggerYears(rarity);
        String note = node.path("note").asText("").trim();
        return new Seed(UUID.randomUUID().toString(), type, plantedBy, targetCivId,
                world.getYear(), rarity, triggerAfterYears, note);
    }

    /**
     * 发芽年限按稀有度随机抽：60~79 → 100~200 年；80~100 → 200~400 年。
     * 用 {@link ThreadLocalRandom} 而不是引擎那把带种子的 random，避免 AI 异步线程影响世界随机序列。
     */
    static int rollTriggerYears(int rarity) {
        return rarity >= 80
                ? 200 + ThreadLocalRandom.current().nextInt(201)   // 200~400
                : 100 + ThreadLocalRandom.current().nextInt(101);  // 100~200
    }

    /** 发芽最多能被牵涉进来的国家数：稀有度 60~79 → 2 国；80~100 → 5 国。 */
    static int maxAffectedCivs(int rarity) {
        return rarity >= 80 ? 5 : 2;
    }

    /**
     * 每年检查种子是否该发芽：plantedYear + triggerAfterYears &lt;= 当前年即到期。
     * 到期种子先移出列表（避免重复发芽），再异步请 AI 生成后果；回复由
     * {@link #applyPendingSprouts()} 在 tick 线程落地。
     */
    private void tickSeeds(int year) {
        if (!narrateEnabled) {
            return;
        }
        if (aiNarrator == null || narrativeExecutor == null || objectMapper == null) {
            return;
        }
        List<Seed> due = new ArrayList<>();
        List<Seed> voided = new ArrayList<>();
        synchronized (seeds) {
            for (Seed seed : seeds) {
                if ((long) seed.plantedYear() + seed.triggerAfterYears() > year) {
                    continue;
                }
                // 种植者已经灭亡：没人记得这件事了，种子作废（不发芽，直接移出列表）
                if (planterDead(seed)) {
                    voided.add(seed);
                    continue;
                }
                due.add(seed);
            }
            seeds.removeAll(voided);
            seeds.removeAll(due);
        }
        for (Seed seed : voided) {
            System.out.println("[因果种子] 种植者（id=" + seed.plantedBy() + "）已灭亡，种子作废：「"
                    + seed.type() + "」" + seed.note());
        }
        for (Seed seed : due) {
            final String prompt = buildSproutPrompt(seed, year);
            narrativeExecutor.submit(() -> {
                try {
                    String reply = aiNarrator.narrate(prompt);
                    if (reply != null) {
                        pendingSprouts.add(new SproutReply(seed, reply));
                    }
                } catch (Exception e) {
                    System.out.println("[因果种子] 发芽请求失败：" + e.getMessage());
                }
            });
        }
    }

    /**
     * 种子的种植者是否已经不存在或灭亡。
     * plantedBy &le; 0 表示这颗种子没有具体种植者（世界事件），不算作废。
     */
    private boolean planterDead(Seed seed) {
        if (seed.plantedBy() <= 0) {
            return false;
        }
        Civilization planter = findCivilization(seed.plantedBy());
        return planter == null || planter.getPopulation() <= 0;
    }

    /** 拼「发芽」prompt：种子信息 + 当前世界状态 + 稀有度决定后果规模的规则。 */
    private String buildSproutPrompt(Seed seed, int year) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器的世界导演。一颗多年前种下的「因果种子」现在发芽了，请生成它的后果。\n")
                .append("只输出 JSON，不要解释，不要代码块。格式：\n")
                .append("{\"description\":\"发生了什么\",\"effects\":{\"tech\":0,\"population\":0,\"resources\":0},")
                .append("\"affectedCivIds\":[2,3]}\n")
                .append("- effects.tech=科技增减、effects.population=人口增减（作用于 affectedCivIds 里每个文明），")
                .append("effects.resources=全球资源增减；都可以为负，也可以省略\n")
                .append("- affectedCivIds 只能用下面列出的存活文明 id，不要编造\n")
                .append("- 后果规模必须和种子的稀有度挂钩：\n")
                .append("  · 稀有度 60~79：小到中等后果，affectedCivIds 只填 1~2 个国家\n")
                .append("  · 稀有度 80~100：大后果，affectedCivIds 填 3~5 个国家，可能多国联手、战争、甚至灭国\n")
                .append("- affectedCivIds 的数量必须落在上面这个区间里，超出的会被截断\n\n");
        prompt.append("种子信息：\n");
        prompt.append("- 类型：").append(seed.type()).append('\n');
        prompt.append("- 种植者文明 id：").append(seed.plantedBy()).append('\n');
        prompt.append("- 目标文明 id：").append(seed.targetCivId()).append('\n');
        prompt.append("- 稀有度：").append(seed.rarity()).append('\n');
        prompt.append("- 备注：").append(seed.note()).append('\n');
        prompt.append("- 种下年份：").append(seed.plantedYear()).append("（约 ")
                .append(seed.triggerAfterYears()).append(" 年后发芽，当前 ").append(year).append(" 年）\n");
        prompt.append("当前世界状态：\n").append(describeWorldForSeed());
        return prompt.toString();
    }

    /** 世界状态快照，供种子相关 prompt 复用。 */
    private String describeWorldForSeed() {
        StringBuilder sb = new StringBuilder();
        sb.append("- 当前年份：").append(world.getYear()).append('\n');
        sb.append("- 当前时代：").append(ERAS[currentEraIndex]).append('\n');
        sb.append("- 全球资源存量：").append(Math.round(resources)).append('\n');
        sb.append("- 存活文明：\n");
        boolean any = false;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            sb.append("  id=").append(civilization.getId())
                    .append("，名称=").append(civilization.getName())
                    .append("，人口=").append(civilization.getPopulation())
                    .append("，科技=").append(civilization.getTechLevel())
                    .append("，领土=").append(civilization.getTerritory().size()).append("块\n");
            any = true;
        }
        if (!any) {
            sb.append("  （无）\n");
        }
        return sb.toString();
    }

    /** 落地所有已到达的发芽结果；只在 tick 线程调用，单条出错不影响其他条目。 */
    private void applyPendingSprouts() {
        SproutReply reply;
        while ((reply = pendingSprouts.poll()) != null) {
            try {
                applySprout(reply);
            } catch (Exception e) {
                System.out.println("[因果种子] 发芽结果无法处理，已跳过：" + e.getMessage());
            }
        }
    }

    /** 一条发芽结果：套 effects、写一条 AI_EVENT，事件稀有度取种子的稀有度。 */
    private void applySprout(SproutReply sprout) throws Exception {
        Seed seed = sprout.seed();
        JsonNode root = parseJsonObject(sprout.reply());
        String description = root.path("description").asText("").trim();
        if (description.isEmpty()) {
            description = (seed.note() == null || seed.note().isBlank()) ? "因果浮现" : seed.note();
        }
        int year = world.getYear();
        JsonNode effects = root.path("effects");
        long populationDelta = clamp(effects.path("population").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
        int techDelta = (int) clamp(effects.path("tech").asLong(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
        double resourceDelta = clamp(effects.path("resources").asDouble(0), -AI_EFFECT_LIMIT, AI_EFFECT_LIMIT);
        JsonNode affected = root.path("affectedCivIds");
        if (affected.isArray()) {
            // 参与国数按稀有度封顶：60~79 最多 2 国、80~100 最多 5 国，多出来的截断，
            // 免得 AI 把一颗小种子写成"全世界都参与"的大事件
            int limit = maxAffectedCivs(seed.rarity());
            int counted = 0;
            for (JsonNode idNode : affected) {
                if (counted >= limit) {
                    break;
                }
                Civilization civilization = findCivilization(idNode.asLong(-1));
                if (civilization != null && civilization.getPopulation() > 0) {
                    applyEffects(year, civilization, populationDelta, techDelta);
                    counted++;
                }
            }
        }
        if (resourceDelta != 0) {
            resources = clamp(resources + resourceDelta, 0, MAX_STOCK);
        }
        record(year, EventType.AI_EVENT, "【因果·" + seed.type() + "】" + description,
                (int) clamp(seed.rarity(), SEED_MIN_RARITY, 100));
    }

    // ==================== 跳过时代（单人直接执行，不投票） ====================

    /** 跳过时代一次最多跨几个时代。 */
    public static final int MAX_ERA_SKIP_STEPS = 5;

    /**
     * 跳过当前时代：按目标时代的门槛比例放大「玩家自己」的科技值与科技点（不改别的国家，
     * 人口 / 食物 / 资源也不动）。世界平均时代（currentEraIndex）跟着前进。
     * 合法性：targetEraIndex 必须晚于当前时代、最多跨 {@link #MAX_ERA_SKIP_STEPS} 个时代、且小于 ERAS.length。
     * 缩放比例 = ERA_THRESHOLDS[target] / ERA_THRESHOLDS[current]；蒙昧门槛是 0 时取 1（只推时代，不放大数值）。
     *
     * @return 成功时返回一段描述文字；失败时返回错误信息
     */
    public String skipEra(int targetEraIndex) {
        try {
            if (!acquireTickLock()) {
                return BUSY_MESSAGE;
            }
            try {
                return skipEraImpl(targetEraIndex);
            } finally {
                tickLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "操作被中断，请重试";
        }
    }

    private String skipEraImpl(int targetEraIndex) {
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

        // 只放大玩家自己国家的科技与五种资源点，别的国家不动（人口/食物/资源也不动）
        Civilization player = world.findCivilization(PLAYER_CIV_ID);
        if (player != null && player.getPopulation() > 0) {
            long tech = Math.round(player.getTechLevel() * scale);
            player.setTechLevel((int) Math.min(Integer.MAX_VALUE, tech));
            for (String type : Civilization.POINT_TYPES) {
                long points = Math.round((long) player.getPoints(type) * scale);
                player.setPoints(type, (int) Math.min(Integer.MAX_VALUE, points));
            }
        }
        currentEraIndex = targetEraIndex;

        narrateEraSkip(fromEra, toEra, scale);
        return "你的文明跨过漫长岁月，从" + fromEra + "进入了" + toEra
                + "（你的科技与五种资源点 ×" + String.format("%.2f", scale) + "）";
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
    public String resetWorld() {
        try {
            if (!acquireTickLock()) {
                return BUSY_MESSAGE;
            }
            try {
                return resetWorldImpl();
            } finally {
                tickLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "操作被中断，请重试";
        }
    }

    private String resetWorldImpl() {
        // 文明与区域归属：clearCivilizations() 内部会同时把所有区域置为无主
        world.clearCivilizations();
        world.setYear(0);
        currentEraIndex = 0;
        // 重开一局：终局状态复位
        gameOver = false;
        gameOverReason = null;
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
        // 上一局的因果种子与待落地发芽结果一并清空
        pendingSprouts.clear();
        // 上一局的目标更新回复也不能落到新世界上
        pendingGoals.clear();
        synchronized (seeds) {
            seeds.clear();
        }
        // 文明 id 计数与命名计数一并归零：重开后的「人类」必须还是 id=1（玩家文明）
        nextCivilizationId = 1;
        world.resetCivilizationIdCounter();
        // 混乱度每局开局重置为 0，反玩家同盟一并解散
        chaosLevel = 0;
        chaosAccumulated = 0;
        chaosAccumulatedReason = null;
        playerPeaceYears = 0;
        coalitionMembers.clear();
        // 重开后的第一个文明仍是玩家（id=1）：点亮它的领土与一圈邻区，其余回到黑雾
        Civilization player = world.spawnCivilization("人类");
        world.exploreAround(player);
        return "世界已重置，从第 0 年开始。";
    }

    // ==================== 开局选时代 ====================

    /** 一个开局配置：key / 名称 / 国家数范围 / 起始科技点 / 时代下标。 */
    public record StartEra(String key, String label, int minCivs, int maxCivs, int points, int eraIndex) {
    }

    /**
     * 7 个开局，按时代从早到晚。eraIndex 必须与 {@link Civilization#ERAS} 的下标严格对应，
     * 否则选「中世纪」会被放到「文艺复兴」（下标错位一档）。
     * 对应关系：蒙昧时代 0、农业时代 2、古典时代 5、中世纪 6、蒸汽时代 8、信息时代 10、星际时代 12。
     */
    private static final List<StartEra> START_ERAS = List.of(
            new StartEra("savage", "蒙昧", 3, 5, 0, 0),
            new StartEra("agrarian", "农业", 5, 8, 20, 2),
            new StartEra("ancient", "古典", 8, 15, 80, 5),
            new StartEra("medieval", "中世纪", 12, 20, 250, 6),
            new StartEra("industrial", "工业", 20, 30, 600, 8),
            new StartEra("information", "信息", 30, 50, 1200, 10),
            new StartEra("interstellar", "星际", 20, 30, 2000, 12));

    /** 全部开局（只读，供前端列按钮）。 */
    public static List<StartEra> startEras() {
        return START_ERAS;
    }

    /** 按 key 找开局；找不到返回 null。 */
    public static StartEra startEraByKey(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.trim().toLowerCase();
        for (StartEra era : START_ERAS) {
            if (era.key().equals(wanted)) {
                return era;
            }
        }
        return null;
    }

    /**
     * 按所选开局重开一局：清空世界（复用 resetWorld，顺带清事件与种子），
     * 按范围内随机数量生成国家，给每个国家配起始科技点与科技值，并把世界平均时代设成对应下标。
     *
     * @param key 开局 key（savage / agrarian / ancient / medieval / industrial / information / interstellar）
     * @return 给前端显示的提示文字；key 非法时返回 null
     */
    public String newGameWithEra(String key) {
        try {
            if (!acquireTickLock()) {
                return null;  // 返回 null 表示加锁失败
            }
            try {
                return newGameWithEraImpl(key);
            } finally {
                tickLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String newGameWithEraImpl(String key) {
        StartEra cfg = startEraByKey(key);
        if (cfg == null) {
            return null;
        }
        resetWorld();   // 清空 + 生成 id=1 的「人类」，事件 / 种子 / 终局状态都已复位

        int total = cfg.minCivs() + random.nextInt(cfg.maxCivs() - cfg.minCivs() + 1);
        for (int i = 2; i <= total; i++) {
            world.spawnCivilization("文明-" + i, 1000L);
        }

        int eraThreshold = (int) ERA_THRESHOLDS[
                Math.max(0, Math.min(ERA_THRESHOLDS.length - 1, cfg.eraIndex()))];
        for (Civilization civilization : world.getCivilizations()) {
            boolean player = civilization.getId() == PLAYER_CIV_ID;
            // 玩家拿满额起始点；AI 拿 0.5~1.5 倍随机。
            // 五种资源点各给同样多——科技树的消耗是按类型扣的，单一池子盖不住混搭消耗。
            int points = player ? cfg.points()
                    : (int) Math.round(cfg.points() * (0.5 + random.nextDouble()));
            for (String type : Civilization.POINT_TYPES) {
                civilization.setPoints(type, Math.max(0, points));
            }
            // 科技值 = 所选时代的门槛：保证个人时代（eraIndexOf）就是所选时代，
            // 且正好落在 [门槛, 下一档门槛) 区间内，一年推进后不会越级跳时代。
            civilization.setTechLevel(eraThreshold);
        }
        // 每个国家点亮自己的领土与一圈邻区
        for (Civilization civilization : world.getCivilizations()) {
            world.exploreAround(civilization);
        }
        // 开局自动点科技：有起始点数的国家，把点得起的节点按树的先后顺序点掉，
        // 直到点数不够为止，这样开局就"看起来已经发展过"了。
        for (Civilization civilization : world.getCivilizations()) {
            autoSpendStartingPoints(civilization);
        }
        // 世界平均时代 = 所选时代下标
        currentEraIndex = Math.max(0, Math.min(ERAS.length - 1, cfg.eraIndex()));
        return "已按「" + cfg.label() + "」开局：" + total + " 个国家，玩家五种资源点各 " + cfg.points() + "。";
    }

    /**
     * 开局自动消费起始科技点：反复挑「前置已满足、同组未占、点数够」里成本最低的节点点掉，
     * 直到再没有点得起的为止。成本随时代递增（古典 5 &lt; 封建 15 &lt; 工业 50 &lt; 现代 150），
     * 取最低成本即「从古典到高依次点」；同价时保持科技树先序。cost 0 的根与大枝会被最先点掉。
     *
     * <p>与玩家点科技走同一套规则（前置 / 点数），但**跳过同组已占的节点**，
     * 所以开局不会出现「同一互斥组里点两个」的浪费。仅用于开局初始化，不写事件。
     *
     * <p>注意：这里**只解锁节点、不改 techLevel**。节点的 techBonus 是「研发出来的奖励」，
     * 若开局就把它加进科技值，起始科技会超过所选时代的门槛（中世纪 1200 + 数百奖励），
     * 个人时代和世界时代都会被顶到下一档去。
     */
    private void autoSpendStartingPoints(Civilization civilization) {
        while (true) {
            TechNode next = null;
            for (TechNode node : TechTree.getAvailable(civilization)) {
                if (TechTree.isPenalized(node, civilization)) {
                    continue;   // 同组未占：不买同组的第二个
                }
                if (!TechTree.canAfford(civilization, node)) {
                    continue;   // 五种资源点里有一种不够就点不动
                }
                // 总成本最低者优先 = 从古典往高时代依次点；同价保持先序（首次出现的胜出）
                if (next == null || node.cost() < next.cost()) {
                    next = node;
                }
            }
            if (next == null) {
                return;
            }
            TechTree.pay(civilization, next);
            // 只解锁、不加 techLevel：起始科技必须停在所选时代的门槛上（产出系数照常 +×1.2）
            TechTree.unlock(civilization, next);
        }
    }

    private void produce() {
        double habitability = world.getPlanet().getHabitability();
        long population = totalPopulation();
        double techBonus = 1 + totalTech() * 0.03;
        // 逐文明算粮：产出 = 人口 × 人均粮食产出 × 宜居度 × 科技加成 × 性格倍率（商业 +30%）。
        // MAX_STOCK 只限制库存缓冲；当年的粮食满足率另按"产量 - 需求"计算，
        // 否则人口平衡点又会被固定库存上限钉死。
        double produced = 0;
        // 商业文明在自己的份额上多产 30%：折算成"额外人口当量"叠加到资源产出里
        double bonusPopulation = 0;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            double factor = productionFactor(civilization);
            produced += civilization.getPopulation() * profile.foodPerCapita() * habitability
                    * foodTechBonus(civilization) * factor;
            bonusPopulation += civilization.getPopulation() * (factor - 1.0);
        }
        // 资源：总量按科技系数产出（保持原有量级与 MAX_STOCK 上限）
        resources = Math.min(MAX_STOCK, resources + (population + bonusPopulation)
                * profile.resourceYield() * habitability * techBonus);
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
            // 性格：宗教文明人口增长 +20%（只放大增长，不放大衰减）
            if (rate > 0) {
                rate = rate * populationGrowthFactor(civilization);
            }
            // 记下本年增长率：民怨判定要用（增长过快 → 民怨 +1）
            civilization.setLastGrowthRate(rate);
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
            int before = civilization.getTechLevel();
            // 性格：科技至上文明的科技增长 ×2（研究倾向 ×2）
            int after = (int) Math.floor(before + growth * techGrowthFactor(civilization));
            if (after > before) {
                // 科技自然增长：科技 +Δ，科技点同额 +Δ
                civilization.gainTech(after - before);
            }
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
     * 每年一次的科技树推进：AI 文明（以及被动模式下的玩家）只要科技点够某个可研究节点，就点掉它。
     * 主动模式下玩家文明的选题权在玩家手里，这里不替它选。
     */
    private void tickResearch() {
        int year = world.getYear();
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            boolean player = civilization.getId() == PLAYER_CIV_ID;
            if (player && isResearchActive()) {
                continue;
            }
            TechNode next = pickResearch(civilization);
            if (next == null) {
                // 没有点得起的节点（或都解锁了）：先攒科技点
                continue;
            }
            unlockNode(year, civilization, next);
        }
    }

    /**
     * 挑一个「点得起」的研究项目：只在「科技点 ≥ 实际成本」的可研究节点里选（实际成本含同组 ×2 惩罚）。
     * 好战文明优先军事，和平文明优先经济/社会；偏好分支没有可选项时，退回全部可点节点里随机。
     */
    private TechNode pickResearch(Civilization civilization) {
        List<TechNode> affordable = new ArrayList<>();
        for (TechNode node : TechTree.getAvailable(civilization)) {
            if (TechTree.canAfford(civilization, node)) {
                affordable.add(node);
            }
        }
        if (affordable.isEmpty()) {
            return null;
        }
        // 一档：性格偏好的分支；二档：好战 / 和平口径；都没有才退回全部可点节点
        List<TechNode> pool = byBranchPreference(affordable,
                node -> prefersBranch(civilization.getPersonality(), node.branch()));
        if (pool.isEmpty()) {
            boolean warlike = isWarlike(civilization);
            pool = byBranchPreference(affordable, node -> warlike
                    ? TechTree.BRANCH_MILITARY.equals(node.branch())
                    : TechTree.BRANCH_ECONOMY.equals(node.branch())
                            || TechTree.BRANCH_SOCIETY.equals(node.branch()));
        }
        if (pool.isEmpty()) {
            pool = affordable;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /** 按给定判据筛出一批节点；一个都没有时返回空表（由调用方决定下一档）。 */
    private static List<TechNode> byBranchPreference(List<TechNode> nodes, java.util.function.Predicate<TechNode> match) {
        List<TechNode> picked = new ArrayList<>();
        for (TechNode node : nodes) {
            if (match.test(node)) {
                picked.add(node);
            }
        }
        return picked;
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

    /**
     * 花五种资源点解锁一个节点：按实际消耗扣点、解锁（顺带把对应产出系数 ×1.2）、
     * 科技 +techBonus，写一条世界事件。调用方需保证
     * {@link TechTree#canAfford(Civilization, TechNode)} 为真。
     */
    private void unlockNode(int year, Civilization civilization, TechNode node) {
        TechTree.pay(civilization, node);
        TechTree.unlock(civilization, node);
        civilization.setTechLevel(civilization.getTechLevel() + node.techBonus());
        // 科技突破滋养文化，也带来威望
        civilization.addCulture(CULTURE_ON_TECH_BREAKTHROUGH);
        civilization.addPrestige(PRESTIGE_ON_TECH_BREAKTHROUGH);
        // 模板：【A】的学者发明了【XXX】，举国欢庆。
        record(year, EventType.AI_EVENT,
                "【" + civilization.getName() + "】的学者发明了【" + node.name() + "】，举国欢庆"
                        + ("（" + civilization.personalityLabel() + "，科技 +" + node.techBonus() + "）")
                        + "。",
                RESEARCH_RARITY);
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
        // 灾难也带背景（本地数据拼）：时代 + 世界科技水平 —— 科技越高，天灾越像"未来感"的灾祸
        record(year, EventType.DISASTER,
                "【" + civilization.getName() + "】遭遇" + name + "，人口减少约"
                        + Math.round(severity * 100) + "%（" + ERAS[currentEraIndex] + "，世界总科技 "
                        + Math.round(tech) + "）。",
                MajorEventRules.disasterRarity(severity));
    }

    private void checkWar(int year) {
        // 只按存活文明数量判断是否可能存在战争
        int aliveCount = world.aliveCivilizationCount();
        if (aliveCount < 2 || totalTech() < profile.warTechThreshold()) {
            return;
        }
        Civilization a = randomAliveCivilization();
        if (a == null) {
            return;
        }
        // 规则战争同样降频，AI 决策里的「攻打」才是主要来源；再按发起方性格缩放概率
        // （好战 ×3、和平 ×0.3、宗教 ×1.5）
        if (random.nextDouble() >= 0.04 * FALLBACK_EVENT_FACTOR * warChanceFactor(a)) {
            return;
        }
        Civilization b = randomAliveCivilization();
        if (a == null || b == null || a == b) {
            return;
        }
        // 与 AI 决策里的战争同一套口径：按双方实力比分档结算
        String detail = resolveWar(year, a, b);
        int jitter = random.nextInt(MajorEventRules.WAR_RARITY_JITTER + 1);
        // 规则战争也带背景（本地数据拼，不调 AI）
        record(year, EventType.WAR,
                "【" + a.getName() + "】（" + backgroundOf(a, b) + "）与【" + b.getName() + "】爆发战争（"
                        + detail + "）。",
                MajorEventRules.warRarity(totalTech(), jitter));
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
        civilization.gainTech(techGain);
        // 科技突破滋养文化（+10），也带来威望（+15）
        civilization.addCulture(CULTURE_ON_TECH_BREAKTHROUGH);
        civilization.addPrestige(PRESTIGE_ON_TECH_BREAKTHROUGH);
        food = Math.max(0, food * 1.1);
        resources = Math.max(0, resources * 1.1);
        record(year, EventType.TECH_BREAKTHROUGH,
                "【" + civilization.getName() + "】的学者取得突破，举国欢庆"
                        + "（" + civilization.personalityLabel() + "，科技 +" + techGain + "）。",
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

    /**
     * 世界平均科技：存活文明 techLevel 的平均值。
     *
     * <p>与 {@link #totalTech()}（总和）区分开：总和会随国家数翻倍，只适合做
     * 「世界总科技」这类展示 / 灾难 / 战争口径；世界时代判定必须用这个平均值，
     * 才能和 {@link Civilization#ERA_THRESHOLDS} 的单文明量级对齐。
     */
    private double averageTechLevel() {
        long sum = 0;
        int alive = 0;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;   // 只算存活文明，避免灭亡文明把平均拉低
            }
            sum += civilization.getTechLevel();
            alive++;
        }
        return alive == 0 ? 0 : (double) sum / alive;
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
