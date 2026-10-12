package com.planet.evolution.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Memory;
import com.planet.evolution.model.Player;
import com.planet.evolution.model.Region;
import com.planet.evolution.service.PlayerService;
import com.planet.evolution.simulation.EventType;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.TechNode;
import com.planet.evolution.simulation.TechTree;
import com.planet.evolution.model.World;

/**
 * 玩家自然语言指挥：用 AI 把一句话翻译成结构化指令，再在本地执行。
 * AI 只负责"翻译"，所有世界改动仍由本地代码完成。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    /** 没有 sessionId / 找不到玩家时，退回的默认玩家文明 id（兼容旧调用）。 */
    private static final long PLAYER_CIV_ID = 1L;
    /** develop_tech 每次增加的科技。 */
    private static final int DEVELOP_TECH_GAIN = 50;
    /** develop_tech 顺带给的科技点（科学点）。 */
    private static final int DEVELOP_TECH_SCIENCE_GAIN = 20;
    /** 打赢一场仗的威望奖励（与 EvolutionEngine 的口径一致）。 */
    private static final int PRESTIGE_WIN_WAR = 30;
    /** 建新城（扩张一块领土）的威望奖励。 */
    private static final int PRESTIGE_NEW_CITY = 20;
    /** attack 造成的损失比例。 */
    private static final double ATTACK_SEVERITY = 0.20;
    /** 玩家方在战争中的损失比例。 */
    private static final double ATTACK_SELF_LOSS = 0.10;
    /** 攻击类事件稀有度（>= 60，属重大事件）。 */
    private static final int RARITY_ATTACK = 70;
    /** 其余指令的事件稀有度（< 60）。 */
    private static final int RARITY_PEACEFUL = 50;
    /** 核子时代的科技门槛，与 EvolutionEngine.ERA_THRESHOLDS[11]（ERAS[11] = "核子时代"）一致。 */
    private static final int NUKE_TECH_REQUIRED = 7000;

    /**
     * 自由行动（free）的能力门槛：说法里命中关键词 → 需要解锁的科技树节点。
     *
     * <p>后端终判：检查 player.getUnlockedTechs() 里是否有对应的节点 id，有则放行，无则拒绝。
     *
     * <p>列表按门槛**从高到低**排列，命中最高的那一档为准。
     */
    private record CapabilityGate(String requiredNodeId, String label, List<String> keywords) {
    }

    /** 能力门槛表（与 prompt 里那张表同源，后端终判用）。 */
    private static final List<CapabilityGate> CAPABILITY_GATES = List.of(
            // 星际航行 / 月球基地 → 火箭节点（spc_rocket）解锁后可飞出地球、登月、殖民火星
            new CapabilityGate("spc_rocket", "星际航行",
                    List.of("星际航行", "月球基地", "飞出太阳系", "殖民火星", "曲率")),
            // 卫星 / 空间站 → 卫星节点（spc_satellite）解锁后可造卫星、建空间站
            new CapabilityGate("spc_satellite", "卫星与空间站",
                    List.of("卫星", "空间站")),
            // 核弹 → 核弹节点（mil_nuke）解锁后可核打击
            new CapabilityGate("mil_nuke", "核弹",
                    List.of("核弹", "核武器", "核打击")),
            // 火箭（飞出地球）→ 火箭节点
            new CapabilityGate("spc_rocket", "火箭",
                    List.of("火箭", "飞出地球", "飞出大气层", "进入太空", "上太空", "航天", "登月")),
            // 电力 / 汽车 → 自动化节点（eco_automation）解锁后可建电网、造汽车
            new CapabilityGate("eco_automation", "电力与汽车",
                    List.of("电力", "发电", "汽车", "内燃机", "电报")),
            // 蒸汽机 / 铁路 → 铁路节点（eco_railway）解锁后可建铁路
            new CapabilityGate("eco_railway", "蒸汽机与铁路",
                    List.of("蒸汽机", "铁路", "火车")),
            // 火药 / 大航海 → 指南针节点（spc_compass）解锁后可远洋航行
            new CapabilityGate("spc_compass", "火药与大航海",
                    List.of("火药", "大航海", "远洋", "印刷")),
            // 冶金 / 城市 / 长城 → 工厂节点（eco_factory）解锁后可建城市设施
            new CapabilityGate("eco_factory", "冶金与城市",
                    List.of("冶金", "钢铁", "城市", "长城", "铸剑")),
            // 农业 / 文字 → 农业改良节点（eco_agriculture）解锁后可发展农业
            new CapabilityGate("eco_agriculture", "农业与文字",
                    List.of("农业", "灌溉")),
            // 文字 / 车轮 → 哲学节点（soc_philosophy）解锁后可发明文字
            new CapabilityGate("soc_philosophy", "文字与车轮",
                    List.of("文字", "房屋", "车轮")));
    /** 核打击事件的稀有度（最高档）。 */
    private static final int RARITY_NUKE = 100;
    /** 塞进 prompt 的最近事件条数：条数太多会把指令规则淹没。 */
    private static final int PROMPT_EVENT_LIMIT = 12;
    /** 四柱单次变化的绝对值上限，与 prompt 里告诉 AI 的 -10~+10 一致；越界一律夹回。 */
    private static final int PILLAR_DELTA_LIMIT = 10;
    /** 四柱崩溃事件的稀有度：>= 60 属重大事件，前端会高亮。 */
    private static final int RARITY_CRASH = 70;
    /** free（自由行动）单次 tech 变化上限（绝对值）。 */
    private static final long FREE_TECH_LIMIT = 5000L;
    /** free 单次 population 变化上限（绝对值）。 */
    private static final long FREE_POPULATION_LIMIT = 1_000_000L;
    /** free 单次 food / resources 变化上限（绝对值）。 */
    private static final long FREE_STOCK_LIMIT = 5_000_000L;
    /** 已知的固定动作；AI 给出别的（含空的 unknown）时一律回退为 free。 */
    private static final Set<String> KNOWN_ACTIONS = Set.of(
            "ask", "attack", "nuke", "expand", "develop_tech", "research", "list_research", "diplomacy", "free");

    // ==================== 外交回应（action=diplomacy） ====================

    /** 玩家对外交的态度：同意。 */
    private static final String STANCE_AGREE = "agree";
    /** 玩家对外交的态度：拒绝。 */
    private static final String STANCE_REJECT = "reject";
    /** 玩家对外交的态度：开战。 */
    private static final String STANCE_WAR = "war";
    /** 开战类回应造成的关系恶化。 */
    private static final int DIPLOMACY_WAR_RELATION = -20;
    /** 拒绝"给好处"类外交（通商 / 送礼 / 求援）只掉 5 关系。 */
    private static final int DIPLOMACY_MILD_REJECT = -5;
    /** 拒绝其余外交掉 10 关系。 */
    private static final int DIPLOMACY_HARSH_REJECT = -10;
    /** 通商成功：双方资源柱各 +5。 */
    private static final int DIPLOMACY_TRADE_RESOURCE_GAIN = 5;
    /** 援助对方：世界资源按此比例扣减。 */
    private static final double DIPLOMACY_AID_RESOURCE_COST = 0.95;

    private final World world;
    private final EvolutionEngine engine;
    private final AiNarrator aiNarrator;
    private final WorldMapper worldMapper;
    private final ObjectMapper objectMapper;
    /** 在线玩家服务：按 sessionId 找到「这次指令是谁发的」，再定位其当前控制的文明。 */
    private final PlayerService playerService;
    private final ReentrantLock commandLock = new ReentrantLock();

    public ChatController(World world, EvolutionEngine engine, AiNarrator aiNarrator,
            WorldMapper worldMapper, ObjectMapper objectMapper, PlayerService playerService) {
        this.world = world;
        this.engine = engine;
        this.aiNarrator = aiNarrator;
        this.worldMapper = worldMapper;
        this.objectMapper = objectMapper;
        this.playerService = playerService;
    }

    /** 抢指令锁的最长等待：AI 调用已挪到锁外，单次持锁只做内存操作，正常远低于此值。 */
    private static final long COMMAND_LOCK_TIMEOUT_MILLIS = 500L;
    /** 抢指令锁的重试间隔。 */
    private static final long COMMAND_LOCK_RETRY_MILLIS = 50L;
    /** 抢不到锁时的提示。 */
    private static final String BUSY_MESSAGE = "世界正忙，请重试";
    /** 等待过程中被中断的提示。 */
    private static final String INTERRUPTED_MESSAGE = "操作被中断，请重试";
    /** 认不出「本次指令的玩家文明」时的提示。 */
    private static final String NO_CIV_MESSAGE = "找不到你控制的文明（可能还没分配、尚未诞生或已灭亡）";

    /**
     * 抢指令锁：每 {@link #COMMAND_LOCK_RETRY_MILLIS} ms 重试一次，最多等
     * {@link #COMMAND_LOCK_TIMEOUT_MILLIS} ms；仍拿不到返回 false（调用方回「世界正忙」）。
     */
    private boolean acquireCommandLock() throws InterruptedException {
        long deadline = System.currentTimeMillis() + COMMAND_LOCK_TIMEOUT_MILLIS;
        do {
            if (commandLock.tryLock(COMMAND_LOCK_RETRY_MILLIS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    /** 抢不到锁时的统一返回。 */
    private static CommandResult busyResult() {
        return new CommandResult(null, BUSY_MESSAGE);
    }

    /** 玩家下达指令。 */
    @PostMapping("/command")
    public CommandResult command(@RequestBody(required = false) CommandRequest request) {
        String text = request == null ? null : request.text();
        // 本次指令是谁发的：据此定位该玩家当前控制的文明（多玩家时不能再写死 id=1）
        String sessionId = request == null ? null : request.sessionId();
        if (text == null || text.isBlank()) {
            return new CommandResult(null, "指令为空");
        }
        return executeCommand(text, sessionId);
    }

    /**
     * 执行一条指令。为了让「外交」这类轻量操作不被别人的 AI 调用堵住，锁被拆成三段：
     * <ol>
     *   <li><b>持锁</b>：认出玩家 + 拼 prompt（只读世界）；</li>
     *   <li><b>放锁</b>：调 AI（1~3 秒网络调用）+ 解析回复（纯计算，不碰世界）；</li>
     *   <li><b>再持锁</b>：把改动落到世界（见 {@link #applyCommand}）。</li>
     * </ol>
     */
    private CommandResult executeCommand(String text, String sessionId) {
        // ---------- 第 1 段：持锁，认出玩家并拼 prompt ----------
        String prompt;
        try {
            if (!acquireCommandLock()) {
                return busyResult();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(null, INTERRUPTED_MESSAGE);
        }
        try {
            Civilization player = playerCivilization(sessionId);
            if (player == null) {
                return new CommandResult(null, NO_CIV_MESSAGE);
            }
            if (aiNarrator == null) {
                return new CommandResult(null, "AI 未启用，无法翻译指令");
            }
            prompt = buildPrompt(text, player);
        } finally {
            commandLock.unlock();
        }

        // ---------- 第 2 段：不持锁，调 AI 并解析 ----------
        String reply = aiNarrator.narrate(prompt);
        if (reply == null) {
            return new CommandResult(null, "AI 未返回结果（可能未配置 API Key 或调用失败）");
        }

        ParsedCommand cmd;
        try {
            cmd = parseCommand(reply);
        } catch (Exception e) {
            // 本地小模型（如 qwen2.5:3b）经常不按 JSON 输出：把 AI 原文透给前端，才看得出它到底说了什么
            return new CommandResult(null, "指令失败：AI 说了：" + previewAiReply(reply));
        }

        // ---------- 第 3 段：再持锁，落地改动 ----------
        try {
            if (!acquireCommandLock()) {
                return busyResult();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(null, INTERRUPTED_MESSAGE);
        }
        try {
            return applyCommand(text, sessionId, cmd);
        } finally {
            commandLock.unlock();
        }
    }

    /** 把 AI 回复解析成结构化指令；纯计算、不碰世界，调用时不需要持锁。 */
    private ParsedCommand parseCommand(String reply) throws Exception {
        JsonNode node = parseJson(reply);
        String action = node.path("action").asText("free").trim().toLowerCase();
        // free 是默认动作：AI 给了未知 / 空动作时一律当 free，绝不落到「无法理解」
        if (!KNOWN_ACTIONS.contains(action)) {
            action = "free";
        }
        String scope = parseScope(node.path("scope"));
        String target = node.path("target").asText("").trim();
        // 只有 action=ask 会用到 reply；其他 action 即使带了 reply 也一律忽略（applyCommand 不读它）
        String replyText = node.path("reply").asText("").trim();
        // 只有 action=diplomacy 会用到 stance；缺失或认不出时按原话关键词兜底（见 normalizeStance）
        String stance = node.path("stance").asText("").trim().toLowerCase();
        // 四柱变化由 AI 顺手判断，缺失或非法一律当 0，绝不让它影响指令本身的执行
        int[] pillarDelta = parsePillarDeltas(node.path("pillars"));
        // free（自由行动）相关字段
        boolean freeFailed = false;
        String freeReason = "";
        String freeNarrative = "";
        String freeEvent = "";
        long[] freeEffects = new long[4];
        List<String> freeNewRegions = new ArrayList<>();
        if ("free".equals(action)) {
            freeFailed = node.path("failed").asBoolean(false);
            freeReason = node.path("reason").asText("").trim();
            freeNarrative = node.path("narrative").asText("").trim();
            freeEvent = node.path("event").asText("").trim();
            freeEffects = parseFreeEffects(node.path("effects"));
            freeNewRegions = parseNewRegions(node.path("newRegions"));
        }
        return new ParsedCommand(action, scope, target, replyText, stance, pillarDelta,
                freeFailed, freeReason, freeNarrative, freeEvent, freeEffects, freeNewRegions);
    }

    /**
     * 持锁执行：把一条已解析的指令落到世界（关系值、人口、科技、事件、四柱、因果种子…）。
     * 只覆盖内存操作，正常 &lt; 10ms；由 {@link #executeCommand} 保证调用时持有指令锁。
     */
    private CommandResult applyCommand(String text, String sessionId, ParsedCommand cmd) {
        // AI 调用期间世界可能推进过：重新认一次玩家，避免把改动落到已经灭亡 / 已被切走的文明上
        Civilization player = playerCivilization(sessionId);
        if (player == null) {
            return new CommandResult(null, NO_CIV_MESSAGE);
        }

        String action = cmd.action();
        String scope = cmd.scope();
        String target = cmd.target();
        List<Long> targetCivIds = expandScope(scope, target, player);
        // 只看清单的指令（list_research，以及没带科技名的 research）不算治国动作，不参与四柱变化
        boolean viewOnly = "list_research".equals(action) || ("research".equals(action) && target.isBlank());

        // 本次指令产生的战斗：边执行边收集，最后原样回给前端播动画
        List<CombatEvent> combatEvents = new ArrayList<>();
        // 混乱度：本条指令的累计从零开始（单次上限按整条指令算）
        if (engine != null) {
            engine.beginChaos();
        }

        String result;
        switch (action) {
            case "ask" -> result = cmd.replyText().isEmpty()
                    ? "我还没想好怎么回答，换个问法再问我一次。"
                    : cmd.replyText();
            case "attack" -> {
                if (targetCivIds == null || targetCivIds.isEmpty()) {
                    result = attackNoTargetMessage(player, target, combatEvents);
                } else {
                    result = doAttack(player, targetCivIds, combatEvents);
                }
            }
            case "nuke" -> {
                if (targetCivIds == null || targetCivIds.isEmpty()) {
                    result = "没听清要核平谁。可以说「核平全世界」「核平最强的」这类话，我再判断。";
                } else {
                    result = doNuke(player, targetCivIds, combatEvents);
                }
            }
            case "develop_tech" -> result = doDevelopTech(player);
            // research 没带科技名（「研究」这种没头没尾的）→ 按「看清单」处理，不报「没听清要研究什么」
            case "research" -> result = target.isBlank() ? doListResearch(player) : doResearch(player, target);
            case "list_research" -> result = doListResearch(player);
            // 外交回应：目标不由 AI 决定，取引擎里挂起的「最近一条外交」，stance 只区分同意/拒绝/开战
            case "diplomacy" -> result = doDiplomacy(player, cmd.stance(), text, combatEvents);
            case "expand" -> {
                if ("ONE".equals(scope) && !target.isEmpty()) {
                    result = doExpandTo(player, target);
                } else if ("ALL".equals(scope)) {
                    result = doExpandAll(player);
                } else {
                    result = doExpand(player);
                }
            }
            // 自由行动：玩家说什么都能尝试。能力门槛由后端终判（检查科技树节点），AI 只负责"想干什么"。
            case "free" -> {
                CapabilityGate gate = matchCapabilityGate(text);
                String gateFail = capabilityGateFailure(player, text);
                if (gateFail != null) {
                    result = gateFail;
                } else if (gate != null && cmd.freeFailed()) {
                    // 门槛其实过了，AI 却判成失败：以后端为准，放行
                    result = doFree(player, false, cmd.freeReason(), cmd.freeNarrative(), cmd.freeEvent(),
                            cmd.freeEffects(), cmd.freeNewRegions());
                } else {
                    result = doFree(player, cmd.freeFailed(), cmd.freeReason(), cmd.freeNarrative(),
                            cmd.freeEvent(), cmd.freeEffects(), cmd.freeNewRegions());
                }
            }
            default -> result = "我试着照做，但没能理解出可行的结果，换种说法再试试。";
        }

        // 指令执行完，再把四柱变化落到玩家身上。
        // ask 只是问问题、看清单（viewOnly）也只是查看，都不动国政，所以不吃四柱变化；玩家已经灭亡也就不必再算了。
        if (!"ask".equals(action) && !viewOnly && player.getPopulation() > 0) {
            String crash = applyPillarsAndCheckCrash(player, cmd.pillarDelta());
            if (crash != null) {
                result = result + "\n" + crash;
            }
        }

        // 关键事件后：让 AI 判断这次指令是否留下长期后果（因果种子）。
        // 问问题 / 看清单不算治国动作，不种种子。
        if (engine != null && !"ask".equals(action) && !viewOnly) {
            engine.considerSeed((int) player.getId(), "玩家指令「" + text + "」，结果是：" + result);
        }
        return new CommandResult(result, null, combatEvents,
                engine == null ? null : engine.consumeChaosChange());
    }

    // ==================== 四柱（参考《王权》） ====================

    /**
     * 把本次指令的四柱变化应用到玩家文明，然后判定崩溃。
     *
     * @return 崩溃提示（拼到给玩家的回复里）；没崩溃返回 null
     */
    private String applyPillarsAndCheckCrash(Civilization player, int[] delta) {
        int[] before = {player.getScience(), player.getWelfare(), player.getResource(), player.getMilitary()};
        // 取值 0~100 的夹取由 Civilization 的 setter 负责，这里只管加
        player.setScience(before[0] + delta[0]);
        player.setWelfare(before[1] + delta[1]);
        player.setResource(before[2] + delta[2]);
        player.setMilitary(before[3] + delta[3]);
        return checkPillarCrash(player, before);
    }

    /**
     * 四柱崩溃判定：某一柱 <= 0 或 >= 100 就崩溃。
     *
     * <p>只认「本次指令把柱子推过了界」：柱子已经停在 0/100 时不再重复触发。
     * 否则一根贴底的柱子会让之后每条指令都减半一次人口，几个回合就把玩家玩死。
     *
     * <p>崩溃后果：人口减半（一次指令只减一次，不管同时崩了几柱）+ 写一条 PLAYER_COMMAND 事件。
     *
     * @param before 本次指令之前的四柱值，顺序为 science / welfare / resource / military
     * @return 崩溃提示；没崩溃返回 null
     */
    private String checkPillarCrash(Civilization player, int[] before) {
        List<String> messages = new ArrayList<>();
        if (crossedFloor(before[0], player.getScience())) {
            messages.add("文明退化回蒙昧");
        } else if (crossedCeiling(before[0], player.getScience())) {
            messages.add("盛极而衰，周边文明联合起来对抗你");
        }
        if (crossedFloor(before[1], player.getWelfare())) {
            messages.add("人民暴动，你的文明陷入内乱");
        } else if (crossedCeiling(before[1], player.getWelfare())) {
            messages.add("盛极而衰，周边文明联合起来对抗你");
        }
        if (crossedFloor(before[2], player.getResource())) {
            messages.add("大饥荒，人口锐减");
        } else if (crossedCeiling(before[2], player.getResource())) {
            messages.add("盛极而衰，周边文明联合起来对抗你");
        }
        if (crossedFloor(before[3], player.getMilitary())) {
            messages.add("外敌趁虚而入");
        } else if (crossedCeiling(before[3], player.getMilitary())) {
            messages.add("盛极而衰，周边文明联合起来对抗你");
        }
        if (messages.isEmpty()) {
            return null;
        }

        player.setPopulation(player.getPopulation() / 2);
        String text = String.join("；", messages);
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "的王国崩溃：" + text + "（人口减半）", RARITY_CRASH);
        return "【王国崩溃】" + text + "（人口减半，当前人口 " + player.getPopulation() + "）";
    }

    /** 本次指令把这一柱从上方推到触底。 */
    private static boolean crossedFloor(int before, int after) {
        return before > Civilization.PILLAR_MIN && after <= Civilization.PILLAR_MIN;
    }

    /** 本次指令把这一柱从下方推到爆满。 */
    private static boolean crossedCeiling(int before, int after) {
        return before < Civilization.PILLAR_MAX && after >= Civilization.PILLAR_MAX;
    }

    /**
     * 解析 AI 给的 pillars 字段，顺序 science / welfare / resource / military。
     * 字段缺失、类型不对、或者根本不是对象时一律返回全 0——四柱只是附加效果，解析失败不该让指令失败。
     * 每项夹到 ±{@link #PILLAR_DELTA_LIMIT}。
     */
    private int[] parsePillarDeltas(JsonNode pillars) {
        int[] delta = new int[4];
        if (pillars == null || !pillars.isObject()) {
            return delta;
        }
        delta[0] = parsePillarDelta(pillars.path("science"));
        delta[1] = parsePillarDelta(pillars.path("welfare"));
        delta[2] = parsePillarDelta(pillars.path("resource"));
        delta[3] = parsePillarDelta(pillars.path("military"));
        return delta;
    }

    /** 单个柱子的变化量：容忍 5 / 5.0 / "+5" / "5" 这些写法，其余按 0 处理。 */
    private int parsePillarDelta(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        int value;
        if (node.isNumber()) {
            value = (int) Math.round(node.asDouble());
        } else {
            // 模型常写成 "+5" 或 "5"，NumberInput 不认前导加号，这里先剥掉
            String raw = node.asText("").trim().replace("+", "");
            try {
                value = (int) Math.round(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return Math.max(-PILLAR_DELTA_LIMIT, Math.min(PILLAR_DELTA_LIMIT, value));
    }

    /**
     * attack 没能展开出文明 id 时的提示：先分清「目标是区域、但那里没人」和「根本没听懂」，
     * 免得玩家说「进攻南美北部」（无主）却被当成没识别出来。
     *
     * @param target AI 翻译出的目标名字（scope=ONE 时才有意义）
     */
    private String attackNoTargetMessage(Civilization player, String target, List<CombatEvent> combatEvents) {
        if (target != null && !target.trim().isEmpty()) {
            Region region = findRegionByName(target);
            if (region != null) {
                if (region.isUnowned()) {
                    return region.getName() + "是无主区域，没有可攻击的文明。想占领它请说「占领"
                            + region.getName() + "」。";
                }
                Civilization owner = findCivilization(region.getOwnerCivId());
                if (owner != null && owner.getId() == player.getId()) {
                    return region.getName() + "是你自己的领土，没有可攻击的文明。";
                }
                if (owner != null && owner.getPopulation() > 0) {
                    // 有主且归属者存活：本不该走到这里，兜底按归属文明正常打
                    return doAttack(player, List.of(owner.getId()), combatEvents);
                }
                return region.getName() + "的归属文明已经灭亡，没有可攻击的文明。";
            }
        }
        return "没听清要打谁。可以说「核平全世界」「灭了最强的」「打南亚」这类话，我再判断。";
    }

    /**
     * 攻打 targetCivIds 里的每个文明：接壤是物理限制，必须保留，所以只打它与玩家接壤的那块边境区域；
     * 不设实力门槛——想打就打，胜负按双方人口对比分四档：
     * ratio &gt; 3 守方直接灭亡且区域归玩家；ratio &gt; 1.5 守方损失 60%，跌破灭绝线即灭亡并占地；
     * 0.7 &le; ratio &le; 1.5 双方各损失 30%；ratio &lt; 0.7 玩家损失 40%。
     */
    private String doAttack(Civilization player, List<Long> targetCivIds, List<CombatEvent> combatEvents) {
        if (targetCivIds == null || targetCivIds.isEmpty()) {
            return "没听清要打谁。可以说「攻打南亚」「打旁边的」这类话，我再判断。";
        }
        List<String> messages = new ArrayList<>();
        // 同时打多个国家算「大范围攻击」
        if (engine != null && targetCivIds.size() > 1) {
            engine.addChaos(10, "大范围攻击");
        }
        for (Long civId : targetCivIds) {
            if (player.getPopulation() <= 0) {
                messages.add("你的文明已经灭亡，后续目标不再进攻。");
                break;
            }
            messages.add(attackOne(player, civId, combatEvents));
        }
        return String.join("\n", messages);
    }

    /** 对单个文明发起一次进攻：只打它与玩家接壤的那块边境区域，然后按四档人口比结算。 */
    private String attackOne(Civilization player, long defenderCivId, List<CombatEvent> combatEvents) {
        Civilization defender = findCivilization(defenderCivId);
        if (defender == null) {
            return "找不到 id=" + defenderCivId + " 的文明，跳过。";
        }
        if (defender.getId() == player.getId()) {
            return "那是你自己的文明，跳过。";
        }
        if (defender.getPopulation() <= 0) {
            return defender.getName() + "已经灭亡了，跳过。";
        }
        Region region = borderRegionOf(player, defender);
        if (region == null) {
            return defender.getName() + "与你的领土不接壤，无法进攻。";
        }

        int year = world.getYear();

        // 战斗确实发生了（找到了接壤的边境区域）：直接记一条给前端播动画
        combatEvents.add(new CombatEvent((int) player.getId(), (int) defender.getId(),
                region.getId(), "attack"));
        // 战争标记：双方和平年数清零，年内标记为「卷入战争」（状态指标据此加民怨 / 紧张度、扣文化）
        if (engine != null) {
            engine.noteWar(player, defender);
        }

        long playerPopulation = player.getPopulation();
        long defenderPopulation = defender.getPopulation();
        double ratio = defenderPopulation <= 0 ? Double.MAX_VALUE
                : (double) playerPopulation / defenderPopulation;

        double defenderLossRate;
        double playerLossRate;
        String balance;
        // 碾压档：守方必定灭亡，不走灭绝线判定
        boolean crushed = false;
        if (ratio > 3.0) {
            // 玩家人口 > 守方 × 3：守方直接灭亡，区域归玩家
            crushed = true;
            defenderLossRate = 1.0;
            playerLossRate = 0.0;
            balance = "碾压";
        } else if (ratio > 1.5) {
            // 玩家人口 > 守方 × 1.5：守方损失 60%，跌破灭绝线就灭亡并占地
            defenderLossRate = 0.60;
            playerLossRate = 0.0;
            balance = "占优";
        } else if (ratio >= 0.7) {
            // 玩家人口在守方 × 0.7 ~ 1.5：各损失 30%
            defenderLossRate = 0.30;
            playerLossRate = 0.30;
            balance = "势均力敌";
        } else {
            // 玩家人口 < 守方 × 0.7：玩家损失 40%
            defenderLossRate = 0.0;
            playerLossRate = 0.40;
            balance = "劣势";
        }

        long defenderLoss = Math.round(defenderPopulation * defenderLossRate);
        long playerLoss = Math.round(playerPopulation * playerLossRate);
        long defenderLeft = Math.max(0, defenderPopulation - defenderLoss);
        long playerLeft = Math.max(0, playerPopulation - playerLoss);
        // 威望：损失更小的一方算「打赢了」
        if (defenderLossRate >= playerLossRate) {
            player.addPrestige(PRESTIGE_WIN_WAR);
        } else {
            defender.addPrestige(PRESTIGE_WIN_WAR);
        }

        long extinctionLine = engine.getProfile().extinctionPopulation();
        boolean defenderDied = crushed || defenderLeft < extinctionLine;
        boolean playerDied = playerLeft < extinctionLine;
        // 混乱度：主动宣战 +5；打盟约国（关系 > 50）+20；把对方打灭国再 +10
        if (engine != null) {
            boolean betrayedAlly = player.relationTo(defender.getId()) > 50;
            engine.addChaos(betrayedAlly ? 20 : 5, betrayedAlly ? "违反盟约" : "主动宣战");
            if (defenderDied) {
                engine.addChaos(10, "灭国");
            }
        }

        if (defenderDied) {
            defender.setPopulation(0L);
            record(year, EventType.EXTINCTION, defender.getName() + "人口凋零，文明灭亡",
                    com.planet.evolution.simulation.MajorEventRules.extinctionRarity());
        } else {
            defender.setPopulation(defenderLeft);
        }

        if (playerDied) {
            player.setPopulation(0L);
            record(year, EventType.EXTINCTION, player.getName() + "人口凋零，文明灭亡",
                    com.planet.evolution.simulation.MajorEventRules.extinctionRarity());
        } else {
            player.setPopulation(playerLeft);
        }

        // 只有玩家还活着才谈得上吞并
        boolean annexed = defenderDied && !playerDied;
        if (annexed) {
            annex(player, region);
        }

        int playerLossPercent = (int) Math.round(playerLossRate * 100);
        int defenderLossPercent = (int) Math.round(defenderLossRate * 100);
        StringBuilder message = new StringBuilder();
        message.append("你进攻").append(defender.getName()).append("的").append(region.getName())
                .append("（").append(balance).append("）：你损失约").append(playerLossPercent)
                .append("%人口，对方损失约").append(defenderLossPercent).append("%人口");
        if (playerDied) {
            message.append("。你损失惨重，文明已经灭亡。");
        } else if (crushed) {
            message.append("。对方毫无还手之力，直接灭国，你吞并了").append(region.getName()).append("。");
        } else if (annexed) {
            message.append("。对方灭国，你吞并了").append(region.getName()).append("。");
        } else if (defenderDied) {
            message.append("。对方灭国。");
        } else {
            message.append("。双方均未灭国，领土未变。");
        }

        // 带背景：谁、什么性格、跟对方什么关系
        record(year, EventType.PLAYER_COMMAND,
                "【" + player.getName() + "】（" + player.personalityLabel() + "，对" + defender.getName()
                        + "关系" + player.relationTo(defender.getId()) + "）进攻【" + defender.getName() + "】的"
                        + region.getName()
                        + "（" + balance + "，双方损失 " + playerLossPercent + "% / " + defenderLossPercent + "%）"
                        + (playerDied ? "，玩家文明灭亡" : annexed ? "，灭其国并吞并该地" : ""),
                RARITY_ATTACK);
        return message.toString();
    }

    /** 目标文明与玩家领土接壤的边境区域；没有任何一块接壤就返回 null。 */
    private Region borderRegionOf(Civilization player, Civilization defender) {
        for (Integer regionId : defender.getTerritory()) {
            Region region = world.getRegionMap().get(regionId);
            if (region != null && bordersPlayerTerritory(player, region)) {
                return region;
            }
        }
        return null;
    }

    /** 文明与玩家的关系：自己 / 接壤 / 不接壤。 */
    private String relationOf(Civilization player, Civilization civilization) {
        if (civilization.getId() == player.getId()) {
            return "自己";
        }
        return borderRegionOf(player, civilization) != null ? "接壤" : "不接壤";
    }

    /**
     * 核打击 targetCivIds 里的每个文明：人口清零，所占区域全部变无主（ownerCivId = 0）。
     * 门槛看世界总科技（对齐 EvolutionEngine.ERA_THRESHOLDS[11]，ERAS[11] = "核子时代"），不看玩家自己的科技。
     */
    private String doNuke(Civilization player, List<Long> targetCivIds, List<CombatEvent> combatEvents) {
        int tech = worldTech();
        if (tech < NUKE_TECH_REQUIRED) {
            return "世界还没进入核子时代（需要总科技 " + NUKE_TECH_REQUIRED + "，当前 " + tech + "）";
        }
        if (targetCivIds == null || targetCivIds.isEmpty()) {
            return "没听清要核平谁。可以说「核平全世界」「核平最强的」这类话，我再判断。";
        }

        int year = world.getYear();
        List<String> hit = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Long civId : targetCivIds) {
            Civilization victim = findCivilization(civId);
            if (victim == null) {
                skipped.add("id=" + civId + "（不存在）");
                continue;
            }
            if (victim.getPopulation() <= 0) {
                skipped.add(victim.getName() + "（已灭亡）");
                continue;
            }
            // 先把目标占的区域全部改为无主，再从它的领土表里摘掉
            List<Integer> nukedRegions = new ArrayList<>(victim.getTerritory());
            for (Integer regionId : nukedRegions) {
                Region owned = world.getRegionMap().get(regionId);
                if (owned != null) {
                    owned.setOwnerCivId(Region.UNOWNED);
                }
                victim.removeRegion(regionId);
            }
            if (nukedRegions.isEmpty()) {
                // 已经没有领土了也记一条（regionId = 0），前端照样播核爆
                combatEvents.add(new CombatEvent((int) player.getId(), (int) victim.getId(), 0, "nuke"));
            } else {
                // 每块易主的区域各记一条，前端可以把每个区域都炸一遍
                for (Integer regionId : nukedRegions) {
                    combatEvents.add(new CombatEvent((int) player.getId(), (int) victim.getId(),
                            regionId, "nuke"));
                }
            }
            victim.setPopulation(0L);
            record(year, EventType.EXTINCTION, victim.getName() + "遭核打击，彻底毁灭",
                    com.planet.evolution.simulation.MajorEventRules.extinctionRarity());
            hit.add(victim.getName());
        }

        // 核打击是最大的混乱源：每灭掉一个国家 +15（单条指令上限由引擎钳制）
        if (engine != null && !hit.isEmpty()) {
            engine.addChaos(15 * hit.size(), "核打击");
        }

        if (hit.isEmpty()) {
            return "没有可以核平的目标：" + String.join("、", skipped) + "。";
        }
        record(year, EventType.PLAYER_COMMAND,
                "【" + player.getName() + "】（" + player.personalityLabel() + "）对【"
                        + String.join("、", hit) + "】发动核打击。",
                RARITY_NUKE);

        String names = String.join("、", hit);
        StringBuilder message = new StringBuilder();
        message.append("你向").append(names).append("投下核弹，").append(names).append("从地图上消失了。");
        if (!skipped.isEmpty()) {
            message.append("（跳过：").append(String.join("、", skipped)).append("）");
        }
        return message.toString();
    }

    /** 发展科技：科技 +N，顺带攒一点科学点，混乱度 -1。 */
    private String doDevelopTech(Civilization player) {
        player.gainTech(DEVELOP_TECH_GAIN);
        if (engine != null) {
            engine.addChaos(-1, "发展科技");
        }
        player.addPoints(Civilization.POINT_SCIENCE, DEVELOP_TECH_SCIENCE_GAIN);
        String message = "你投入资源发展科技，科技 +" + DEVELOP_TECH_GAIN
                + "（当前科技 " + player.getTechLevel() + "，科学点 +" + DEVELOP_TECH_SCIENCE_GAIN + "）。";
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "集中资源发展科技（科技 +" + DEVELOP_TECH_GAIN + "）", RARITY_PEACEFUL);
        return message;
    }

    // ==================== 科技树指令 ====================

    /**
     * 研究某个科技：按中文名找节点。已解锁 / 前置没解锁 / 同互斥组已占 / 科技点不够都给提示，
     * 够就花科技点解锁，并返回剩余科技点。
     */
    private String doResearch(Civilization player, String target) {
        if (target == null || target.isBlank()) {
            return "没听清要研究什么。可以说「研究火器」「研究银行」，或者问「有什么能研究」。";
        }
        TechNode node = TechTree.getByName(target);
        if (node == null) {
            return "科技树里没有「" + target.trim() + "」。问一句「有什么能研究」可以看当前能研究的方向。";
        }
        if (player.hasUnlockedTech(node.id())) {
            return "【" + node.name() + "】已经解锁过了。";
        }
        if (node.prerequisite() != null && !player.hasUnlockedTech(node.prerequisite())) {
            TechNode prerequisite = TechTree.getById(node.prerequisite());
            String prereqName = prerequisite != null ? prerequisite.name() : node.prerequisite();
            return "需要先解锁 " + prereqName + "。";
        }
        // 同组惩罚：同 exclusiveGroup 里已选过别的节点时，每种资源的实际消耗 ×2
        Map<String, Integer> costs = TechTree.actualCosts(node, player);
        if (!TechTree.canAfford(player, costs)) {
            return "资源点不够（需要 " + TechTree.costText(costs)
                    + "，当前 " + TechTree.costText(player.pointMap()) + "）。";
        }
        TechTree.pay(player, costs);
        TechTree.unlock(player, node);
        // 解锁奖励：科技 +techBonus
        player.setTechLevel(player.getTechLevel() + node.techBonus());
        // 闷头搞研究属于内政，混乱度 -1
        if (engine != null) {
            engine.addChaos(-1, "内政");
        }
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "解锁了【" + node.name() + "】", RARITY_PEACEFUL);
        return "已解锁【" + node.name() + "】，剩余 " + TechTree.costText(player.pointMap()) + "。";
    }

    /** 列出当前可研究的科技（按分支分组，标出花费）与当前科技点。 */
    private String doListResearch(Civilization player) {
        List<TechNode> available = TechTree.getAvailable(player);
        if (available.isEmpty()) {
            return "当前没有可研究的科技：能解锁的都已经解锁了。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前可研究（共 ").append(available.size()).append(" 项，你有 ")
                .append(TechTree.costText(player.pointMap())).append("）：\n");
        for (String branch : TechTree.branches()) {
            List<String> names = new ArrayList<>();
            for (TechNode node : available) {
                if (node.branch().equals(branch)) {
                    names.add(node.name() + "（" + TechTree.costText(TechTree.actualCosts(node, player))
                            + (TechTree.isPenalized(node, player) ? "，同组已选 ×2" : "") + "）");
                }
            }
            if (!names.isEmpty()) {
                sb.append("- ").append(TechTree.branchLabel(branch)).append("：")
                        .append(String.join("、", names)).append('\n');
            }
        }
        // 根节点等不属于四大分支的（分支名不在 branches 里）单独列一行
        List<String> others = new ArrayList<>();
        for (TechNode node : available) {
            if (!TechTree.branches().contains(node.branch())) {
                others.add(node.name() + "（" + TechTree.costText(TechTree.actualCosts(node, player)) + "）");
            }
        }
        if (!others.isEmpty()) {
            sb.append("- 基础：").append(String.join("、", others)).append('\n');
        }
        sb.append("已解锁 ").append(player.getUnlockedTechs().size()).append(" 项。"
                + "说「研究某个科技名」即可花资源点解锁。");
        return sb.toString();
    }

    /** 扩张：占一块与现有领土接壤的无主区域。 */
    private String doExpand(Civilization player) {
        List<Region> candidates = new ArrayList<>();
        for (Region region : world.getRegionMap().all()) {
            if (region.isUnowned() && bordersPlayerTerritory(player, region)) {
                candidates.add(region);
            }
        }
        if (candidates.isEmpty()) {
            return "没有与领土接壤的无主区域可以扩张。";
        }
        Region region = candidates.get(0);
        annex(player, region);
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "扩张至" + region.getName(), RARITY_PEACEFUL);
        return "你的领土扩张到了" + region.getName() + "（当前 " + player.getTerritory().size() + " 块区域）。";
    }

    /** 一口气占掉所有能连到的无主区域：从玩家领土向外一圈圈扩散，直到没有新的可占区域。 */
    private String doExpandAll(Civilization player) {
        int taken = 0;
        while (true) {
            List<Region> batch = new ArrayList<>();
            for (Region region : world.getRegionMap().all()) {
                if (region.isUnowned() && bordersPlayerTerritory(player, region)) {
                    batch.add(region);
                }
            }
            if (batch.isEmpty()) {
                break;
            }
            for (Region region : batch) {
                annex(player, region);
                taken++;
            }
        }
        if (taken == 0) {
            return "没有和你领土接壤的无主区域可以扩张。";
        }
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "一口气吞并了" + taken + "块无主区域", RARITY_PEACEFUL);
        return "你一口气吞并了 " + taken + " 块无主区域（当前 " + player.getTerritory().size() + " 块区域）。";
    }

    /**
     * 把区域划归该文明，并让探索圈随领土一起向外扩一圈。
     * 「占领」「扩张」和灭国吞并都走这里，所以只需在这一处调 exploreAround。
     */
    private void annex(Civilization civilization, Region region) {
        region.setOwnerCivId((int) civilization.getId());
        civilization.addRegion(region.getId());
        world.exploreAround(civilization);
    }

    /** 指定区域名扩张：精确占领玩家点名的那块接壤无主区域。 */
    private String doExpandTo(Civilization player, String regionName) {
        Region region = findRegionByName(regionName);
        if (region == null) {
            return "找不到区域「" + regionName + "」。";
        }
        if (!region.isUnowned()) {
            Civilization owner = findCivilization(region.getOwnerCivId());
            String ownerName = owner != null ? owner.getName() : "其他势力";
            return region.getName() + " 已经被 " + ownerName + " 占了。";
        }
        if (!bordersPlayerTerritory(player, region)) {
            return region.getName() + " 和你的领土不接壤，打不到。";
        }
        annex(player, region);
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "扩张至" + region.getName(), RARITY_PEACEFUL);
        return "你的领土扩张到了" + region.getName() + "（当前 " + player.getTerritory().size() + " 块区域）。";
    }

    // ==================== 自由行动（free） ====================

    /** 命中说法里的能力门槛；没命中返回 null（= 这件事不受科技门槛限制）。 */
    private static CapabilityGate matchCapabilityGate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (CapabilityGate gate : CAPABILITY_GATES) {   // 已按门槛从高到低排列
            for (String keyword : gate.keywords()) {
                if (text.contains(keyword)) {
                    return gate;
                }
            }
        }
        return null;
    }

    /**
     * 后端终判能力门槛：命中门槛且玩家未解锁对应科技树节点时返回失败提示，够则返回 null（放行）。
     */
    private static String capabilityGateFailure(Civilization player, String text) {
        CapabilityGate gate = matchCapabilityGate(text);
        if (gate == null) {
            return null;
        }
        if (player.getUnlockedTechs().contains(gate.requiredNodeId())) {
            return null;
        }
        TechNode node = TechTree.getById(gate.requiredNodeId());
        String nodeName = node != null ? node.name() : gate.label();
        return "你的文明还没解锁【" + nodeName + "】（需要先研究对应的科技），这件事暂时做不到。";
    }

    /**
     * 自由行动：玩家说什么都能尝试。AI 已按能力门槛判断可行性，这里只负责把结果落地。
     *
     * <p>failed=true 时直接返回 reason，不改动世界；否则把 effects 应用到玩家文明（tech、population）
     * 与世界（food、resources），按 newRegions 动态新增区域，写一条 PLAYER_COMMAND 事件，并返回 narrative。
     *
     * @param effects 顺序为 tech / population / food / resources，已夹到单次上限
     */
    private String doFree(Civilization player, boolean failed, String reason, String narrative,
            String event, long[] effects, List<String> newRegions) {
        if (failed) {
            return reason == null || reason.isBlank()
                    ? "你的文明当前还做不到这件事。"
                    : reason;
        }

        int year = world.getYear();

        // tech → 玩家文明（int，不低于 0）；增长时科技点同额增长
        if (effects[0] != 0) {
            long tech = (long) player.getTechLevel() + effects[0];
            int after = (int) Math.max(0, Math.min(Integer.MAX_VALUE, tech));
            int delta = after - player.getTechLevel();
            if (delta > 0) {
                player.gainTech(delta);
            } else if (delta < 0) {
                player.setTechLevel(after);
            }
        }
        // population → 玩家文明（long，不低于 0）
        if (effects[1] != 0) {
            player.setPopulation(Math.max(0, player.getPopulation() + effects[1]));
        }
        // food / resources → 世界（double，不低于 0）；引擎唯一写入口是 restoreState，时代下标原样保留
        if (effects[2] != 0 || effects[3] != 0) {
            engine.restoreState(
                    Math.max(0, engine.getFood() + effects[2]),
                    Math.max(0, engine.getResources() + effects[3]),
                    engine.currentEraIndex());
        }

        // newRegions → 动态新建区域，归属玩家，邻接取玩家已有的一块领土
        List<String> created = new ArrayList<>();
        if (newRegions != null && !newRegions.isEmpty()) {
            List<Integer> territory = player.getTerritory();
            List<Integer> neighbors = territory.isEmpty()
                    ? new ArrayList<>()
                    : new ArrayList<>(List.of(territory.get(0)));
            for (String name : newRegions) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                Region region = world.getRegionMap().addDynamicRegion(name.trim(), (int) player.getId(), neighbors);
                region.setExplored(true);
                player.addRegion(region.getId());
                created.add(region.getName());
            }
            if (!created.isEmpty()) {
                world.exploreAround(player);
            }
        }

        // 事件：优先用 AI 给的 event，其次 narrative
        String description = !event.isBlank() ? event : !narrative.isBlank() ? narrative : "玩家自由行动";
        record(year, EventType.PLAYER_COMMAND, player.getName() + "：" + description, RARITY_PEACEFUL);

        // 返回 narrative；AI 没给就兜底一句
        return narrative.isBlank() ? "你尝试了这项行动，世界因此有了变化。" : narrative;
    }

    /** 解析 free 的 effects：tech / population / food / resources，缺失或非法按 0，单项夹到单次上限。 */
    private long[] parseFreeEffects(JsonNode effects) {
        long[] out = new long[4];
        if (effects == null || !effects.isObject()) {
            return out;
        }
        out[0] = clampFree(parseLong(effects.path("tech")), FREE_TECH_LIMIT);
        out[1] = clampFree(parseLong(effects.path("population")), FREE_POPULATION_LIMIT);
        out[2] = clampFree(parseLong(effects.path("food")), FREE_STOCK_LIMIT);
        out[3] = clampFree(parseLong(effects.path("resources")), FREE_STOCK_LIMIT);
        return out;
    }

    /** 解析 free 的 newRegions：只保留非空字符串。 */
    private List<String> parseNewRegions(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                String name = item.asText().trim();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** 宽容地把 JSON 值读成 long：数字、"123"、"+123"、"1,234" 都认，其余按 0。 */
    private long parseLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        if (node.isNumber()) {
            return Math.round(node.asDouble());
        }
        String raw = node.asText("").trim().replace("+", "").replace(",", "");
        try {
            return Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 把变化量夹到 ±limit。 */
    private static long clampFree(long value, long limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    // ==================== 外交回应（action=diplomacy） ====================

    /** 当前世界混乱度（前端进度条轮询这个接口，0~100）。 */
    @GetMapping("/chaos")
    public ChaosState chaos() {
        return new ChaosState(engine == null ? 0 : engine.getChaosLevel());
    }

    /** 混乱度对外视图。 */
    public record ChaosState(int chaosLevel) {
    }

    /** 当前待回应的 AI 外交（前端「外交提示条」轮询这个接口）。没有时 pending=false。 */
    @GetMapping("/diplomacy")
    public DiplomacyState diplomacy() {
        EvolutionEngine.PendingDiplomacy pending = engine == null ? null : engine.getPendingDiplomacy();
        if (pending == null) {
            return DiplomacyState.none();
        }
        // prompt 已经拼好（如「文明-3请求结盟」），前端直接接在「有人在等你回应：」后面
        return new DiplomacyState(true, pending.civId(), pending.civName(), pending.action(),
                pending.actionLabel(), pending.civName() + pending.summary(), pending.year());
    }

    /**
     * 回应「最近一条」待处理的 AI 外交。
     *
     * <p>针对哪条外交不由 AI 决定，而是取引擎里挂起的那条；AI 只负责把玩家的话归类成
     * 同意 / 拒绝 / 开战（stance），识别不出时用关键词兜底。
     */
    private String doDiplomacy(Civilization player, String stance, String text,
            List<CombatEvent> combatEvents) {
        if (engine == null) {
            return "AI 外交未启用。";
        }
        EvolutionEngine.PendingDiplomacy pending = engine.getPendingDiplomacy();
        if (pending == null) {
            return "当前没有待回应的外交。";
        }
        Civilization envoy = findCivilization(pending.civId());
        if (envoy == null || envoy.getPopulation() <= 0) {
            engine.clearDiplomacy();
            return pending.civName() + "已经灭亡，这条外交作废。";
        }

        EvolutionEngine.DiplomacyAction action = parseDiplomacyAction(pending.action());
        String resolved = normalizeStance(stance, text);
        int year = world.getYear();
        String civName = envoy.getName();

        String result;
        if (STANCE_WAR.equals(resolved)) {
            // 玩家选择开战：无论对方提的是什么，直接开打，关系恶化
            result = "你对" + civName + "开战。" + doAttack(player, List.of(envoy.getId()), combatEvents);
            rememberBothWays(player, envoy, year, "开战",
                    "我们对" + civName + "开战", player.getName() + "对我们开战", -1.0, DIPLOMACY_WAR_RELATION);
        } else if (STANCE_AGREE.equals(resolved)) {
            result = agreeDiplomacy(player, envoy, action, combatEvents);
        } else {
            result = rejectDiplomacy(player, envoy, action);
        }

        engine.clearDiplomacy();
        record(year, EventType.PLAYER_COMMAND,
                "【" + player.getName() + "】回应【" + civName + "】的外交（" + pending.actionLabel() + "）："
                        + (STANCE_WAR.equals(resolved) ? "开战" : STANCE_AGREE.equals(resolved) ? "同意" : "拒绝"),
                RARITY_PEACEFUL);
        return result;
    }

    /** 同意对方的提议：按动作类型执行正面结果。 */
    private String agreeDiplomacy(Civilization player, Civilization envoy,
            EvolutionEngine.DiplomacyAction action, List<CombatEvent> combatEvents) {
        int year = world.getYear();
        String civName = envoy.getName();
        String playerName = player.getName();
        // 和平外交（结盟 / 送礼 / 联姻 / 通商 / 援助）让世界缓和一点；宣战档不算
        if (engine != null && action != EvolutionEngine.DiplomacyAction.WAR) {
            engine.addChaos(-3, action.getLabel());
        }
        switch (action) {
            case ALLIANCE -> {
                rememberBothWays(player, envoy, year, "结盟",
                        "我们与" + civName + "结盟", "我们与" + playerName + "结盟", 1.0, 30);
                return "你接受了" + civName + "的结盟请求，双方关系 +30。";
            }
            case MARRIAGE -> {
                rememberBothWays(player, envoy, year, "联姻",
                        "我们与" + civName + "联姻", "我们与" + playerName + "联姻", 1.0, 30);
                return "你与" + civName + "结为姻亲，双方关系 +30。";
            }
            case GIFT -> {
                rememberBothWays(player, envoy, year, "受礼",
                        "我们收下了" + civName + "的礼物", "我们向" + playerName + "送了礼", 0.5, 20);
                return "你收下了" + civName + "送来的礼物，双方关系 +20。";
            }
            case TRADE -> {
                // 同意通商 → 双方资源 +5%
                bumpResource(player, DIPLOMACY_TRADE_RESOURCE_GAIN);
                bumpResource(envoy, DIPLOMACY_TRADE_RESOURCE_GAIN);
                rememberBothWays(player, envoy, year, "通商",
                        "我们与" + civName + "通商", "我们与" + playerName + "通商", 0.5, 5);
                return "你与" + civName + "开通商路，双方资源各 +5%。";
            }
            case AID -> {
                // 援助对方：付出世界资源，换来关系
                if (engine != null) {
                    engine.restoreState(engine.getFood(),
                            Math.max(0, engine.getResources() * DIPLOMACY_AID_RESOURCE_COST),
                            engine.currentEraIndex());
                }
                rememberBothWays(player, envoy, year, "援助",
                        "我们援助了" + civName, "我们得到了" + playerName + "的援助", 1.0, 15);
                return "你向" + civName + "伸出援手，双方关系 +15（世界资源 -5%）。";
            }
            case REQUEST, PROBE, THREATEN, ULTIMATUM ->
                    // 让步：满足对方的试探 / 威胁 / 最后通牒，关系略回暖，但本质是被拿捏
                    rememberBothWays(player, envoy, year, "让步",
                            "我们对" + civName + "作出让步", "我们对" + playerName + "施压得逞", 0.5, 10);
            case WAR -> {
                // 对方已宣战，玩家选择迎战
                return "你选择迎战。" + doAttack(player, List.of(envoy.getId()), combatEvents);
            }
            default -> {
                return "你同意了" + civName + "的提议。";
            }
        }
        return "你满足了" + civName + "的要求，双方关系 +10。";
    }

    /** 拒绝对方的提议：按动作类型扣关系（给好处类的只扣 5，其余扣 10）。 */
    private String rejectDiplomacy(Civilization player, Civilization envoy,
            EvolutionEngine.DiplomacyAction action) {
        int year = world.getYear();
        String civName = envoy.getName();
        int penalty = switch (action) {
            case TRADE, GIFT, AID -> DIPLOMACY_MILD_REJECT;
            default -> DIPLOMACY_HARSH_REJECT;
        };
        rememberBothWays(player, envoy, year, "拒绝",
                "我们拒绝了" + civName, civName + "被" + player.getName() + "拒绝", -1.0, penalty);
        return "你拒绝了" + civName + "，双方关系 " + penalty + "。";
    }

    /**
     * 双方同时记一笔同类记忆，并各自把对对方的关系调整 {@code relationDelta}。
     *
     * <p>注意 {@link Civilization#remember} 本身会按 {@code sentiment × 10} 顺手改关系，
     * 这里把这部分算进总量，差多少再补 {@code adjustRelation}，保证关系总变化正好等于 relationDelta。
     */
    private void rememberBothWays(Civilization player, Civilization other, int year, String type,
            String playerDesc, String otherDesc, double sentiment, int relationDelta) {
        exchangeMemory(player, other, year, type, playerDesc, sentiment, relationDelta);
        exchangeMemory(other, player, year, type, otherDesc, sentiment, relationDelta);
    }

    /** 记一笔记忆并让 actor 对 target 的关系恰好变化 totalDelta。 */
    private void exchangeMemory(Civilization actor, Civilization target, int year, String type,
            String description, double sentiment, int totalDelta) {
        double clamped = Math.max(-1.0, Math.min(1.0, sentiment));
        int fromMemory = (int) Math.round(clamped * 10);
        actor.remember(new Memory(year, type, (int) target.getId(), description, clamped));
        int rest = totalDelta - fromMemory;
        if (rest != 0) {
            actor.adjustRelation(target.getId(), rest);
        }
    }

    /** 把文明的「资源」柱 +delta（四柱 0~100，setter 自带夹取）。 */
    private static void bumpResource(Civilization civilization, int delta) {
        civilization.setResource(civilization.getResource() + delta);
    }

    /** 还原挂起外交的动作枚举；认不出时退回「提要求」。 */
    private static EvolutionEngine.DiplomacyAction parseDiplomacyAction(String name) {
        if (name == null) {
            return EvolutionEngine.DiplomacyAction.REQUEST;
        }
        try {
            return EvolutionEngine.DiplomacyAction.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return EvolutionEngine.DiplomacyAction.REQUEST;
        }
    }

    /**
     * 归一化玩家态度：优先用 AI 给的 stance（agree / reject / war），
     * 认不出时按原话关键词兜底（开战 &gt; 拒绝 &gt; 同意，先判「不」避免「不同意」被当成同意）。
     */
    private static String normalizeStance(String stance, String text) {
        String s = stance == null ? "" : stance.trim().toLowerCase();
        if (STANCE_AGREE.equals(s) || STANCE_REJECT.equals(s) || STANCE_WAR.equals(s)) {
            return s;
        }
        String t = text == null ? "" : text.trim();
        if (t.contains("开战") || t.contains("宣战") || t.contains("进攻") || t.contains("出兵")) {
            return STANCE_WAR;
        }
        if (t.contains("拒绝") || t.contains("不") || t.contains("否")) {
            return STANCE_REJECT;
        }
        if (t.contains("同意") || t.contains("接受") || t.contains("答应")
                || t.contains("可以") || t.contains("好")) {
            return STANCE_AGREE;
        }
        // 兜底：没听清就当拒绝（不开打、也不白送好处）
        return STANCE_REJECT;
    }

    /**
     * 待回应外交的对外视图。
     *
     * @param pending 是否有待回应外交
     * @param prompt  提示条正文（如「文明-3请求结盟」）
     */
    public record DiplomacyState(boolean pending, long civId, String civName, String action,
            String actionLabel, String prompt, int year) {

        static DiplomacyState none() {
            return new DiplomacyState(false, 0, null, null, null, null, 0);
        }
    }

    /** 目标区域是否与玩家现有领土接壤。 */
    private boolean bordersPlayerTerritory(Civilization player, Region target) {
        for (Integer ownedId : player.getTerritory()) {
            if (target.getNeighbors().contains(ownedId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析「本次指令的玩家文明」。
     *
     * <p>带 sessionId：按它找到玩家，取其当前控制的文明（玩家不存在 / 该文明尚未诞生 / 已灭亡时返回 null）。
     * 没带 sessionId 或找不到该玩家：退回文明 id=1，兼容旧调用。
     */
    private Civilization playerCivilization(String sessionId) {
        if (sessionId != null && !sessionId.isBlank() && playerService != null) {
            Player player = playerService.get(sessionId);
            if (player != null) {
                int civId = player.getControlledCivId();
                if (civId > 0) {
                    return aliveOrNull(findCivilization(civId));
                }
                return null;   // 玩家已登录，但还没分配到文明
            }
        }
        return aliveOrNull(findCivilization(PLAYER_CIV_ID));
    }

    /** 文明存在且人口 &gt; 0 才算「活着的玩家文明」。 */
    private static Civilization aliveOrNull(Civilization civilization) {
        return civilization != null && civilization.getPopulation() > 0 ? civilization : null;
    }

    private Civilization findCivilization(long id) {
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getId() == id) {
                return civilization;
            }
        }
        return null;
    }

    /** 按名字找区域，先精确匹配再包含匹配。 */
    private Region findRegionByName(String name) {
        String wanted = name.trim();
        for (Region region : world.getRegionMap().all()) {
            if (region.getName().equals(wanted)) {
                return region;
            }
        }
        for (Region region : world.getRegionMap().all()) {
            if (region.getName().contains(wanted) || wanted.contains(region.getName())) {
                return region;
            }
        }
        return null;
    }

    /** 「解析失败」提示里回显 AI 原文的长度上限。 */
    private static final int AI_REPLY_PREVIEW_CHARS = 200;

    /**
     * 截取 AI 原始回复的前 {@link #AI_REPLY_PREVIEW_CHARS} 个字，供「解析失败」时回显。
     * 顺手把换行/连续空白压成单个空格，免得撑破前端那一行提示。
     */
    private static String previewAiReply(String reply) {
        if (reply == null) {
            return "(空)";
        }
        String flat = reply.replaceAll("\\s+", " ").trim();
        return flat.length() <= AI_REPLY_PREVIEW_CHARS
                ? flat
                : flat.substring(0, AI_REPLY_PREVIEW_CHARS) + "…";
    }

    /** 容忍模型带 markdown 代码块或前后废话：截取第一个 { 到最后一个 }。 */
    private JsonNode parseJson(String reply) throws Exception {
        int start = reply.indexOf('{');
        System.out.println("=== AI REPLY ===");
        System.out.println(reply);
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("没有找到 JSON 对象");
        }
        String json = reply.substring(start, end + 1);
        // 如果 JSON 不完整（缺少尾部），尝试在最后一个逗号处补齐 "]}"
        if (!json.endsWith("]") && !json.endsWith("}")) {
            int lastComma = json.lastIndexOf(',');
            if (lastComma > start) {
                json = json.substring(0, lastComma + 1) + "]";
            }
        }
        return objectMapper.readTree(json);
    }

    /** 构造交给 AI 的翻译 prompt：玩家原话 + 完整世界状态，让模型按意图挑出文明 id。 */
    private String buildPrompt(String text, Civilization player) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器里的指令翻译器。把玩家的一句话翻译成 JSON，只输出 JSON，不要解释，不要代码块。\n")
                .append("格式：{\"action\":\"ask|attack|nuke|expand|develop_tech|research|list_research|diplomacy|free\",\"scope\":\"ALL|STRONGEST|WEAKEST|NEIGHBOR|SELF|ONE|UNKNOWN\",\"target\":\"具体名字（仅 scope=ONE 时填）\",\"reply\":\"回答内容（仅 action=ask 时填）\",\"stance\":\"agree|reject|war（仅 action=diplomacy 时填）\",\"failed\":false,\"reason\":\"仅 free 且做不到时填\",\"narrative\":\"仅 free 成功时填\",\"effects\":{\"tech\":0,\"population\":0,\"food\":0,\"resources\":0},\"newRegions\":[],\"event\":\"仅 free 成功时填\",\"pillars\":{\"science\":0,\"welfare\":0,\"resource\":0,\"military\":0},\"raw\":\"玩家原话\"}\n")
                .append("action：ask=玩家在问问题，你在 reply 里直接回答；attack=出兵攻打某文明；nuke=核打击某文明；expand=占领相邻无主区域；develop_tech=发展科技；research=研究科技树上的某个科技；list_research=查看当前能研究什么；free=玩家在做其他任何事（默认动作，绝不能输出 unknown）。\n")
                .append("scope 怎么填——AI 只输出范围标签，不再输出 id 列表，id 由后端展开：\n")
                .append("- ALL = 全世界/所有人/所有文明/全部（expand 时指所有能连到的无主区域）\n")
                .append("- STRONGEST = 最强的/最厉害的/最大的\n")
                .append("- WEAKEST = 最弱的/最小的\n")
                .append("- NEIGHBOR = 旁边的/隔壁的/邻居\n")
                .append("- ONE = 指定了具体名字，名字填在 target\n")
                .append("- SELF = expand 和 develop_tech 用\n")
                .append("- UNKNOWN = 看不懂\n")
                .append("- 绝对不要输出文明 id 列表\n\n")
                .append("target 怎么填——仅在 scope=ONE 时填，是具体的文明名字或区域名：\n")
                .append("- attack 和 nuke 的 ONE：按名字找文明，target 填文明名\n")
                .append("- expand 的 ALL：玩家说「占领全世界」「占领所有无主区域」「占满」「都占了」→ scope=ALL，target 留空，后端会把所有和领土连得上的无主区域一次占完\n")
                .append("- expand 的 ONE：玩家指定了区域名（比如「占领中亚」「往南亚扩张」），target 填那个区域名\n")
                .append("- expand 的 SELF：玩家只说「扩张」「占一块地」没指定区域名，target 留空\n")
                .append("- develop_tech 的 SELF：表示作用于自己，target 留空\n\n")
                .append("research / list_research 怎么用（科技树）——先分清「点名研究某个科技」和「问能研究什么」：\n")
                .append("- list_research（看清单）：玩家在问「能研究什么」，触发词例如「有什么能研究」「能研究什么」「可研究列表」「研究选项」「我现在能研究啥」「科技树还有什么」「有哪些科技能研究」「能研究的都有啥」「看看能研究什么」，以及任何「问可研究科技」的说法 → action=list_research，scope=SELF，target 留空，reply 留空\n")
                .append("- research（点名研究）：玩家指定了具体科技名，触发词例如「研究青铜武器」「研发火药」「点核弹科技」「把激光武器搞出来」→ action=research，scope=ONE，target 填科技的中文名（只填名字，不要带「研究」「研发」「点」「科技」这些字）\n")
                .append("- 关键判断：research 必须带具体科技名。玩家只说了「研究」「研发」却没带科技名（比如「研究」「研究一下」）→ 不要当成 research，当成 list_research\n")
                .append("- 例外：问可研究科技的句子即使带疑问词（有什么 / 什么 / 哪些 / 能……吗），也一律用 list_research，不要用 ask、也不要用 research\n")
                .append("- research 的 target 必须照抄下面「可研究」里给出的科技名，绝对不要编造名字；玩家想要的科技不在里面时，仍旧输出 action=research 和玩家说的名字，后端会给出提示\n")
                .append("- list_research 只是查看清单，pillars 四根柱子全给 0\n\n")
                .append("diplomacy（回应外交）——只在「世界状态」里出现「待回应的外交」时才可能用到：\n")
                .append("- 玩家在回应那条外交时（同意 / 接受 / 答应 / 拒绝 / 不 / 开战 / 宣战）→ action=diplomacy\n")
                .append("- stance 只填 agree（同意 / 接受 / 答应）、reject（拒绝 / 不）、war（开战 / 宣战）三者之一\n")
                .append("- 针对哪条外交、对方是谁，全由后端取「最近一条」决定，你只填 stance，scope 留空或 UNKNOWN、target 留空\n")
                .append("- 世界状态里没有「待回应的外交」时不要输出 diplomacy\n\n")
                .append("action：attack=出兵攻打某文明；nuke=核打击某文明；expand=占领相邻无主区域；develop_tech=发展科技；research=研究某个科技；list_research=查看可研究的科技；diplomacy=回应最近一条待处理的外交；free=其他一切情况（默认，绝不能输出 unknown）。\n")
                .append("scope 列出的含义：ALL=全世界，STRONGEST=最强的，WEAKEST=最弱的，NEIGHBOR=旁边的/邻居，ONE=指定名字，SELF=自己，UNKNOWN=看不懂。\n")
                .append("- 绝对不要输出文明 id 列表；AI 只负责输出 scope 和 target（当 scope=ONE 时），后端由 expandScope 展开具体 id。\n\n")
                .append("- attack 只能打与玩家接壤的文明；nuke 需要世界总科技 ≥ 7000\n\n")
                .append("自由行动（free）——动作判断的铁律：\n")
                .append("- 只有下面这几种「明确指令」才用固定动作：\n")
                .append("  · 「打 / 进攻 / 攻击 XX」→ attack\n")
                .append("  · 「核弹 / 核平 XX」→ nuke\n")
                .append("  · 「占领 / 扩张 XX」→ expand\n")
                .append("  · 「研究 XX」→ research（只带科技名时）\n")
                .append("  · 明确提问（带 吗 / 呢 / 怎么 / 为什么 / 谁 / 什么）→ ask\n")
                .append("- 其他任何情况，一律 action=free。free 是默认选项，不是例外；绝不要返回 unknown，也不要硬套成 expand。\n")
                .append("free 怎么判断能不能做——先查这张能力门槛表（检查玩家是否已解锁对应科技树节点）：\n")
                .append("  星际航行/月球基地/殖民火星 → 需要解锁「火箭」（太空分支）\n")
                .append("  卫星/空间站 → 需要解锁「卫星」（太空分支）\n")
                .append("  核弹/核武器/核打击 → 需要解锁「核弹」（军事分支）\n")
                .append("  火箭/飞出地球/登月 → 需要解锁「火箭」（太空分支）\n")
                .append("  电力/发电/汽车/内燃机 → 需要解锁「自动化」（经济分支）\n")
                .append("  蒸汽机/铁路/火车 → 需要解锁「铁路」（经济分支）\n")
                .append("  火药/大航海/远洋/印刷 → 需要解锁「指南针」（太空分支）\n")
                .append("  冶金/钢铁/城市/长城/铸剑 → 需要解锁「工厂」（经济分支）\n")
                .append("  农业/灌溉 → 需要解锁「农业改良」（经济分支）\n")
                .append("  文字/房屋/车轮 → 需要解锁「哲学」（社会分支）\n")
                .append("  其他行动不受科技门槛限制，可以直接尝试。\n")
                .append("  效果必须符合当前时代：石器时代没有火箭节点，飞不出地球。\n")
                .append("  · 这张表只作参考，最终以后端判定为准：后端会检查玩家的已解锁科技列表，")
                .append("已解锁节点一定放行、未解锁一定失败，所以你不用担心判断错误。\n")
                .append("free 的两种输出：\n")
                .append("  1) 科技不够（做不到）→ {\"action\":\"free\",\"failed\":true,\"reason\":\"你的文明还没掌握XX技术（需要科技 N，当前 M）\"}\n")
                .append("  2) 科技够（做得到）→ {\"action\":\"free\",\"failed\":false,\"narrative\":\"一两句话描述发生了什么\",\"effects\":{\"tech\":0,\"population\":0,\"food\":0,\"resources\":0},\"newRegions\":[],\"event\":\"事件描述\"}\n")
                .append("- 效果必须有代价，不能凭空增加东西：造东西消耗资源和人口，发明消耗资源，探索消耗资源和食物。\n")
                .append("- effects：tech / population 作用在玩家文明，food / resources 作用在世界；不需要的项写 0，可以为负。\n")
                .append("- 数值上限（超出自动截断）：tech 单次 ±5000；population 单次 ±1000000；food / resources 单次 ±5000000。\n")
                .append("- newRegions：只有真的开辟了新地盘才填（例如「飞出地球」→ [\"近地轨道\",\"月球\"]），否则留空数组 []。\n")
                .append("- 明确告诉 AI：玩家说什么就用合理的代价和结果回应。不要拒绝，不要说「做不到」（只有科技不够时用 failed+reason 说明原因）。\n")
                .append("- free 例子：\n")
                .append("  · 石器时代说「飞出地球」→ {\"action\":\"free\",\"failed\":true,\"reason\":\"你的文明还没解锁【火箭】（需要先研究对应的科技），这件事暂时做不到。\"}\n")
                .append("  · 点了火箭科技后说「飞出地球」→ {\"action\":\"free\",\"failed\":false,\"narrative\":\"你的火箭冲出大气层，人类第一次进入太空。\",\"effects\":{\"tech\":500,\"population\":0,\"food\":0,\"resources\":-500000},\"newRegions\":[\"近地轨道\",\"月球\"],\"event\":\"发射火箭，挺进太空\"}\n")
                .append("  · 「建长城」（需要工厂节点）→ 成功，effects.population=-50000，effects.resources=-200000，event:\"建造了长城\"\n")
                .append("  · 「发明文字」（需要哲学节点）→ 成功，effects.tech=100，event:\"发明了文字\"\n")
                .append("  · 「教猴子用火」→ 自己编一个合理结果\n")
                .append("  · 「和文明-3 联姻」→ 自己编一个合理结果\n\n")
                .append("pillars 怎么填——玩家王国有四根柱子（参考《王权》），每根 0~100，初始都是 50。\n")
                .append("你要顺手判断「这条指令会让哪几根柱子怎么动」，填在 pillars 里，四个字段都要给：\n")
                .append("- 发展科技（develop_tech）→ science 加、welfare 减、resource 减\n")
                .append("- 研究科技（research）→ science 加、welfare 减、resource 减；查看清单（list_research）→ 全 0\n")
                .append("- 打仗 / 核弹（attack、nuke）→ military 减、resource 减、welfare 减\n")
                .append("- 扩张 / 占领（expand）→ military 加、resource 减\n")
                .append("- 休养生息、休整、安抚民心这类主动休整指令 → welfare 加、resource 减\n")
                .append("- 每个字段的取值必须在 -10 到 +10 之间（含），按这条指令的力度给，别都给 +10\n")
                .append("- 每根柱子都往上或往下推一点，不要四根全 0；力度小就给 ±1~±3\n")
                .append("- action=ask（玩家只是在问问题）时四根柱子全给 0：问问题不算治国动作\n")
                .append("- 自由行动（action=free）按玩家行动的力度给四柱变化（-10~+10），拿不准就全给 0\n")
                .append("- pillars 只影响玩家自己的王国，和世界总科技、文明人口不是一回事，不要混\n\n")
                .append("action=ask：玩家在问问题、而不是下命令时用它，回答写在 reply 字段里：\n")
                .append("- 问情况 / 问建议 / 问会发生什么（「现在世界怎么样」「哪个文明最强」「我该怎么办」「打他会不会赢」）→ action=ask，reply 里直接写出给玩家的回答\n")
                .append("- 疑问词（吗 / 呢 / 怎么 / 为什么 / 哪 / 谁 / 什么 / 会不会 / 怎么样）→ 大概率是 ask；唯一例外是「问可研究科技」（有什么能研究 / 能研究什么 / 可研究列表 / 研究选项）→ 那是 list_research，不是 ask\n")
                .append("- 动词开头、祈使句（打 / 核 / 占 / 发展 / 研究 / 灭了 / 扩张）→ 大概率是命令，按 scope/target 格式输出\n")
                .append("- 上面这些明确动作之外的一切情况一律 action=free（玩家说什么都能尝试），绝不输出 unknown，也不要硬套成 expand\n")
                .append("- ask 的 reply 用中文写给人看，可以引用上面的世界状态；不要在 reply 里再嵌 JSON，也不要解释格式；ask 时 scope/target 留空或 UNKNOWN 即可，后端不会执行任何动作\n")
                .append("- 所有统计类问题（还剩几个文明、总人口多少、世界总科技多少）直接用世界状态里给出的数字回答，不要自己数、不要估算\n\n");
        prompt.append("世界状态：\n");
        prompt.append("- 年份：").append(world.getYear()).append('\n');
        prompt.append("- 玩家（下指令的人）：id=").append(player.getId())
                .append("，名称=").append(player.getName())
                .append("，人口=").append(player.getPopulation())
                .append("，科技=").append(player.getTechLevel()).append('\n');
        prompt.append("- 玩家王国四柱（0~100，贴近 0 或 100 都会崩溃，越靠近两端越危险）：science=")
                .append(player.getScience()).append("，welfare=").append(player.getWelfare())
                .append("，resource=").append(player.getResource())
                .append("，military=").append(player.getMilitary()).append('\n');
        prompt.append("- 玩家领土：").append(describeRegions(player.getTerritory())).append('\n');
        prompt.append("- 可扩张的相邻无主区域：").append(describeExpandable(player)).append('\n');
        prompt.append("- 玩家科技树：已解锁 ").append(player.getUnlockedTechs().size()).append(" 项")
                .append(player.getUnlockedTechs().isEmpty() ? "" : describeUnlockedTechs(player)).append('\n');
        prompt.append("- 玩家五种资源点：").append(TechTree.costText(player.pointMap()))
                .append("（解锁科技按类型扣点：军事节点吃军事点、社会节点吃文化点、太空节点吃科学点、"
                        + "经济节点吃工业/农业点；花不起后端会提示「资源点不够」）\n");
        prompt.append("- 玩家可研究（research 的 target 只能用这些名字）：")
                .append(describeAvailableResearch(player)).append('\n');
        prompt.append("- 世界总科技：").append(worldTech())
                .append("（核子时代门槛 ").append(NUKE_TECH_REQUIRED).append("）\n");
        prompt.append("- 存活文明总数：").append(aliveCivCountExcludingPlayer(player))
                .append("（不含玩家，人口>0）\n");
        prompt.append("- 世界总人口：").append(totalPopulation()).append('\n');
        // 待回应的外交：让 AI 知道玩家可能在回应它（有才写，没有就不占上下文）
        if (engine != null) {
            EvolutionEngine.PendingDiplomacy pending = engine.getPendingDiplomacy();
            if (pending != null) {
                prompt.append("- 待回应的外交：").append(pending.civName())
                        .append("（id=").append(pending.civId()).append("）").append(pending.actionLabel())
                        .append("，事件：").append(pending.message()).append('\n');
            }
        }
        prompt.append("- 存活文明：\n").append(describeCivs(player));
        prompt.append("- 最近事件（最新在前）：\n").append(describeRecentEvents());
        prompt.append('\n').append("玩家原话：").append(text);
        return prompt.toString();
    }

    private String describeRegions(List<Integer> regionIds) {
        if (regionIds.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer id : regionIds) {
            Region region = world.getRegionMap().get(id);
            if (region != null) {
                sb.append(region.getName()).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private String describeExpandable(Civilization player) {
        StringBuilder sb = new StringBuilder();
        for (Region region : world.getRegionMap().all()) {
            if (region.isUnowned() && bordersPlayerTerritory(player, region)) {
                sb.append(region.getName()).append(' ');
            }
        }
        return sb.length() == 0 ? "（无）" : sb.toString().trim();
    }

    /** 玩家已解锁的科技名（按解锁顺序），给模型当上下文用。 */
    private String describeUnlockedTechs(Civilization player) {
        StringBuilder sb = new StringBuilder("（");
        boolean first = true;
        for (String techId : player.getUnlockedTechs()) {
            TechNode node = TechTree.getById(techId);
            if (node == null) {
                continue;
            }
            if (!first) {
                sb.append('、');
            }
            sb.append(node.name());
            first = false;
        }
        return sb.append('）').toString();
    }

    /** 当前可研究的科技名清单（按分支分组），research 的 target 只能从这里取。 */
    private String describeAvailableResearch(Civilization player) {
        List<TechNode> available = TechTree.getAvailable(player);
        if (available.isEmpty()) {
            return "（无，科技树已全部解锁）";
        }
        List<String> parts = new ArrayList<>();
        for (String branch : TechTree.branches()) {
            List<String> names = new ArrayList<>();
            for (TechNode node : available) {
                if (node.branch().equals(branch)) {
                    names.add(node.name());
                }
            }
            if (!names.isEmpty()) {
                parts.add(TechTree.branchLabel(branch) + "：" + String.join("、", names));
            }
        }
        return String.join("；", parts);
    }

    /** 世界总科技：各文明科技之和，口径与 EvolutionEngine.totalTech() 一致。 */
    private int worldTech() {
        return world.getCivilizations().stream().mapToInt(Civilization::getTechLevel).sum();
    }

    /** 存活文明总数（不含玩家，只算人口 > 0 的）。 */
    private int aliveCivCountExcludingPlayer(Civilization player) {
        int count = 0;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getId() != player.getId() && civilization.getPopulation() > 0) {
                count++;
            }
        }
        return count;
    }

    /** 世界总人口（含玩家，只算存活文明）。 */
    private long totalPopulation() {
        long total = 0;
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() > 0) {
                total += civilization.getPopulation();
            }
        }
        return total;
    }

    /** 存活文明清单：id / 名称 / 人口 / 科技 / 占地块数 / 与玩家关系。 */
    private String describeCivs(Civilization player) {
        StringBuilder sb = new StringBuilder();
        for (Civilization civilization : world.getCivilizations()) {
            if (civilization.getPopulation() <= 0) {
                continue;
            }
            sb.append("  id=").append(civilization.getId())
                    .append("，名称=").append(civilization.getName())
                    .append("，人口=").append(civilization.getPopulation())
                    .append("，科技=").append(civilization.getTechLevel())
                    .append("，占地=").append(civilization.getTerritory().size()).append("块")
                    .append("，与玩家关系=").append(relationOf(player, civilization))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 最近若干条历史事件，最新在前，供模型判断"最近的敌人""打过我的"。 */
    private String describeRecentEvents() {
        List<GameEvent> events = worldMapper.selectEvents();
        if (events == null || events.isEmpty()) {
            return "  （无）\n";
        }
        int from = Math.max(0, events.size() - PROMPT_EVENT_LIMIT);
        StringBuilder sb = new StringBuilder();
        for (int i = events.size() - 1; i >= from; i--) {
            GameEvent event = events.get(i);
            sb.append("  [").append(event.year()).append("年] ")
                    .append(event.type().getLabel()).append('：')
                    .append(event.description()).append('\n');
        }
        return sb.toString();
    }

    /** 解析 scope 字段，默认 "UNKNOWN"，转大写。 */
    private String parseScope(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "UNKNOWN";
        }
        String scope = node.asText().trim().toUpperCase();
        return scope.isEmpty() ? "UNKNOWN" : scope;
    }

    /** 按 scope 和 target 展开为文明 id 列表。 */
    private List<Long> expandScope(String scope, String target, Civilization player) {
        scope = scope.toUpperCase();
        long playerId = player.getId();

        switch (scope) {
            case "ALL":
                // 除玩家外所有存活文明 id
                List<Long> allIds = new ArrayList<>();
                for (Civilization civ : world.getCivilizations()) {
                    if (civ.getId() != playerId && civ.getPopulation() > 0) {
                        allIds.add(civ.getId());
                    }
                }
                return allIds;

            case "STRONGEST":
                // 人口最多的一个（不含玩家）
                Long strongest = null;
                long strongestPop = -1;
                for (Civilization civ : world.getCivilizations()) {
                    if (civ.getId() == playerId || civ.getPopulation() <= 0) continue;
                    if (civ.getPopulation() > strongestPop) {
                        strongestPop = civ.getPopulation();
                        strongest = civ.getId();
                    }
                }
                return strongest != null ? List.of(strongest) : List.of();

            case "WEAKEST":
                // 人口最少的一个（不含玩家）
                Long weakest = null;
                long weakestPop = Long.MAX_VALUE;
                for (Civilization civ : world.getCivilizations()) {
                    if (civ.getId() == playerId || civ.getPopulation() <= 0) continue;
                    if (civ.getPopulation() < weakestPop) {
                        weakestPop = civ.getPopulation();
                        weakest = civ.getId();
                    }
                }
                return weakest != null ? List.of(weakest) : List.of();

            case "NEIGHBOR":
                // 邻居 = 领土直接接壤，或中间只隔 1 块无主区域。
                // 原来只看玩家「第一块」区域的邻居表，玩家其他领土旁边的文明全被漏掉，
                // 于是「打邻国」明明识别对了却返回空列表、报「没听清要打谁」。
                List<Long> neighborIds = new ArrayList<>();
                for (Civilization civ : world.getCivilizations()) {
                    if (civ.getId() == playerId || civ.getPopulation() <= 0) {
                        continue;
                    }
                    // a. 直接接壤：沿用 borderRegionOf（它覆盖玩家的全部领土）
                    boolean neighbor = borderRegionOf(player, civ) != null;
                    if (!neighbor) {
                        // b. 隔一块：该文明的某块领土旁边，有一块「无主区域」与玩家领土接壤
                        for (Integer rid : civ.getTerritory()) {
                            Region own = world.getRegionMap().get(rid);
                            if (own == null) {
                                continue;
                            }
                            for (Integer gapId : own.getNeighbors()) {
                                Region gap = world.getRegionMap().get(gapId);
                                if (gap != null && gap.isUnowned() && bordersPlayerTerritory(player, gap)) {
                                    neighbor = true;
                                    break;
                                }
                            }
                            if (neighbor) {
                                break;
                            }
                        }
                    }
                    if (neighbor) {
                        neighborIds.add(civ.getId());
                    }
                }
                return neighborIds;

            case "ONE":
                // 按 target 找文明：先按文明名，找不到再按区域名反查归属文明
                // （玩家习惯说「进攻南亚」「打欧洲」，而不是先查那里归谁）
                if (target != null && !target.trim().isEmpty()) {
                    Long found = findCivilizationByName(target.trim(), playerId);
                    if (found != null) return List.of(found);

                    Region region = findRegionByName(target.trim());
                    if (region != null && !region.isUnowned()) {
                        Civilization owner = findCivilization(region.getOwnerCivId());
                        // 归属者是玩家自己时不返回：既不能打自己，更不能核自己
                        if (owner != null && owner.getId() != playerId) {
                            return List.of(owner.getId());
                        }
                    }
                    // 区域无主、或它属于玩家自己，都当作没有可打的文明，交给调用方出提示
                }
                // target 为空或没匹配到，返回空列表，让调用方决定如何提示
                return List.of();

            default:
                // UNKNOWN 或其他：返回空列表
                return List.of();
        }
    }

    /** 精确匹配优先，再包含匹配，跳过「当前玩家自己」和已灭亡文明。 */
    private Long findCivilizationByName(String name, long playerCivId) {
        String wanted = name.trim();
        // 先精确匹配
        for (Civilization civ : world.getCivilizations()) {
            if (civ.getId() == playerCivId) continue;
            if (civ.getPopulation() <= 0) continue;
            if (civ.getName().equals(wanted)) {
                return civ.getId();
            }
        }
        // 再包含匹配
        for (Civilization civ : world.getCivilizations()) {
            if (civ.getId() == playerCivId) continue;
            if (civ.getPopulation() <= 0) continue;
            if (civ.getName().contains(wanted) || wanted.contains(civ.getName())) {
                return civ.getId();
            }
        }
        return null;
    }

    private void record(int year, EventType type, String description, int rarity) {
        worldMapper.insertEvent(new GameEvent(year, type, description, rarity));
    }

    /** 请求体。sessionId 可选：带上就能按「该玩家当前控制的文明」执行指令，不带则退回文明 id=1。 */
    public record CommandRequest(String text, String sessionId) {
    }

    /**
     * 一条已解析的指令（AI 回复的落地形态）。解析在锁外完成、落地在锁内完成，
     * 两者靠这个不可变载体传递，避免解析期间一直占着锁。
     */
    private record ParsedCommand(String action, String scope, String target, String replyText, String stance,
            int[] pillarDelta, boolean freeFailed, String freeReason, String freeNarrative,
            String freeEvent, long[] freeEffects, List<String> freeNewRegions) {
    }

    /**
     * 一场具体战斗（供前端直接播动画，不必再靠对比区域归属变化去猜）。
     *
     * @param attackerCivId 进攻方文明 id（一般都是玩家 1）
     * @param defenderCivId 防守方文明 id
     * @param regionId      交战/易主的区域 id（0 表示该文明已无领土）
     * @param type          类型：attack / nuke
     */
    public record CombatEvent(int attackerCivId, int defenderCivId, int regionId, String type) {
    }

    /** 响应体：成功给 result，失败给 error；combatEvents 是本次指令产生的战斗列表；chaosChange 是混乱度变化文本。 */
    public record CommandResult(String result, String error, List<CombatEvent> combatEvents, String chaosChange) {

        /** 兼容原调用点的两参构造：没有战斗、没有混乱度变化。 */
        public CommandResult(String result, String error) {
            this(result, error, List.of(), null);
        }

        /** 兼容原调用点的三参构造：没有混乱度变化。 */
        public CommandResult(String result, String error, List<CombatEvent> combatEvents) {
            this(result, error, combatEvents, null);
        }
    }
}
