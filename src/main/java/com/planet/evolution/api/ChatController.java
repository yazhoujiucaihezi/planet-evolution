package com.planet.evolution.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Region;
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

    /** 玩家文明固定为 id = 1（第一个文明）。 */
    private static final long PLAYER_CIV_ID = 1L;
    /** develop_tech 每次增加的科技。 */
    private static final int DEVELOP_TECH_GAIN = 50;
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
            "ask", "attack", "nuke", "expand", "develop_tech", "research", "list_research", "free");

    private final World world;
    private final EvolutionEngine engine;
    private final AiNarrator aiNarrator;
    private final WorldMapper worldMapper;
    private final ObjectMapper objectMapper;

    public ChatController(World world, EvolutionEngine engine, AiNarrator aiNarrator,
            WorldMapper worldMapper, ObjectMapper objectMapper) {
        this.world = world;
        this.engine = engine;
        this.aiNarrator = aiNarrator;
        this.worldMapper = worldMapper;
        this.objectMapper = objectMapper;
    }

    /** 玩家下达指令。 */
    @PostMapping("/command")
    public CommandResult command(@RequestBody(required = false) CommandRequest request) {
        String text = request == null ? null : request.text();
        if (text == null || text.isBlank()) {
            return new CommandResult(null, "指令为空");
        }
        Civilization player = playerCivilization();
        if (player == null) {
            return new CommandResult(null, "找不到玩家文明（id=1）或它已灭亡");
        }
        if (aiNarrator == null) {
            return new CommandResult(null, "AI 未启用，无法翻译指令");
        }

        String reply = aiNarrator.narrate(buildPrompt(text, player));
        if (reply == null) {
            return new CommandResult(null, "AI 未返回结果（可能未配置 API Key 或调用失败）");
        }

        String action;
        String scope;
        String target;
        String replyText;
        int[] pillarDelta;
        // free（自由行动）相关字段
        boolean freeFailed = false;
        String freeReason = "";
        String freeNarrative = "";
        String freeEvent = "";
        long[] freeEffects = new long[4];
        List<String> freeNewRegions = new ArrayList<>();
        try {
            JsonNode node = parseJson(reply);
            action = node.path("action").asText("free").trim().toLowerCase();
            // free 是默认动作：AI 给了未知 / 空动作时一律当 free，绝不落到「无法理解」
            if (!KNOWN_ACTIONS.contains(action)) {
                action = "free";
            }
            scope = parseScope(node.path("scope"));
            target = node.path("target").asText("").trim();
            // 只有 action=ask 会用到 reply；其他 action 即使带了 reply 也一律忽略（下面的 switch 不读它）
            replyText = node.path("reply").asText("").trim();
            // 四柱变化由 AI 顺手判断，缺失或非法一律当 0，绝不让它影响指令本身的执行
            pillarDelta = parsePillarDeltas(node.path("pillars"));
            if ("free".equals(action)) {
                freeFailed = node.path("failed").asBoolean(false);
                freeReason = node.path("reason").asText("").trim();
                freeNarrative = node.path("narrative").asText("").trim();
                freeEvent = node.path("event").asText("").trim();
                freeEffects = parseFreeEffects(node.path("effects"));
                freeNewRegions = parseNewRegions(node.path("newRegions"));
            }
        } catch (Exception e) {
            return new CommandResult(null, "AI 返回的内容无法解析为指令：" + e.getMessage());
        }

        List<Long> targetCivIds = expandScope(scope, target, player);
        // 只看清单的指令（list_research，以及没带科技名的 research）不算治国动作，不参与四柱变化
        boolean viewOnly = "list_research".equals(action) || ("research".equals(action) && target.isBlank());

        String result;
        switch (action) {
            case "ask" -> result = replyText.isEmpty()
                    ? "我还没想好怎么回答，换个问法再问我一次。"
                    : replyText;
            case "attack" -> {
                if (targetCivIds == null || targetCivIds.isEmpty()) {
                    result = attackNoTargetMessage(player, target);
                } else {
                    result = doAttack(player, targetCivIds);
                }
            }
            case "nuke" -> {
                if (targetCivIds == null || targetCivIds.isEmpty()) {
                    result = "没听清要核平谁。可以说「核平全世界」「核平最强的」这类话，我再判断。";
                } else {
                    result = doNuke(player, targetCivIds);
                }
            }
            case "develop_tech" -> result = doDevelopTech(player);
            // research 没带科技名（「研究」这种没头没尾的）→ 按「看清单」处理，不报「没听清要研究什么」
            case "research" -> result = target.isBlank() ? doListResearch(player) : doResearch(player, target);
            case "list_research" -> result = doListResearch(player);
            case "expand" -> {
                if ("ONE".equals(scope) && !target.isEmpty()) {
                    result = doExpandTo(player, target);
                } else if ("ALL".equals(scope)) {
                    result = doExpandAll(player);
                } else {
                    result = doExpand(player);
                }
            }
            // 自由行动：玩家说什么都能尝试（AI 已按能力门槛判断可行性）
            case "free" -> result = doFree(player, freeFailed, freeReason, freeNarrative, freeEvent,
                    freeEffects, freeNewRegions);
            default -> result = "我试着照做，但没能理解出可行的结果，换种说法再试试。";
        }

        // 指令执行完，再把四柱变化落到玩家身上。
        // ask 只是问问题、看清单（viewOnly）也只是查看，都不动国政，所以不吃四柱变化；玩家已经灭亡也就不必再算了。
        if (!"ask".equals(action) && !viewOnly && player.getPopulation() > 0) {
            String crash = applyPillarsAndCheckCrash(player, pillarDelta);
            if (crash != null) {
                result = result + "\n" + crash;
            }
        }
        return new CommandResult(result, null);
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
    private String attackNoTargetMessage(Civilization player, String target) {
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
                    return doAttack(player, List.of(owner.getId()));
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
    private String doAttack(Civilization player, List<Long> targetCivIds) {
        if (targetCivIds == null || targetCivIds.isEmpty()) {
            return "没听清要打谁。可以说「攻打南亚」「打旁边的」这类话，我再判断。";
        }
        List<String> messages = new ArrayList<>();
        for (Long civId : targetCivIds) {
            if (player.getPopulation() <= 0) {
                messages.add("你的文明已经灭亡，后续目标不再进攻。");
                break;
            }
            messages.add(attackOne(player, civId));
        }
        return String.join("\n", messages);
    }

    /** 对单个文明发起一次进攻：只打它与玩家接壤的那块边境区域，然后按四档人口比结算。 */
    private String attackOne(Civilization player, long defenderCivId) {
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

        long extinctionLine = engine.getProfile().extinctionPopulation();
        boolean defenderDied = crushed || defenderLeft < extinctionLine;
        boolean playerDied = playerLeft < extinctionLine;

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

        record(year, EventType.PLAYER_COMMAND,
                player.getName() + "进攻" + defender.getName() + "的" + region.getName()
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
    private String doNuke(Civilization player, List<Long> targetCivIds) {
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
            for (Integer regionId : new ArrayList<>(victim.getTerritory())) {
                Region owned = world.getRegionMap().get(regionId);
                if (owned != null) {
                    owned.setOwnerCivId(Region.UNOWNED);
                }
                victim.removeRegion(regionId);
            }
            victim.setPopulation(0L);
            record(year, EventType.EXTINCTION, victim.getName() + "遭核打击，彻底毁灭",
                    com.planet.evolution.simulation.MajorEventRules.extinctionRarity());
            hit.add(victim.getName());
        }

        if (hit.isEmpty()) {
            return "没有可以核平的目标：" + String.join("、", skipped) + "。";
        }
        record(year, EventType.PLAYER_COMMAND,
                player.getName() + "对" + String.join("、", hit) + "发动核打击", RARITY_NUKE);

        String names = String.join("、", hit);
        StringBuilder message = new StringBuilder();
        message.append("你向").append(names).append("投下核弹，").append(names).append("从地图上消失了。");
        if (!skipped.isEmpty()) {
            message.append("（跳过：").append(String.join("、", skipped)).append("）");
        }
        return message.toString();
    }

    /** 发展科技。 */
    private String doDevelopTech(Civilization player) {
        player.setTechLevel(player.getTechLevel() + DEVELOP_TECH_GAIN);
        String message = "你投入资源发展科技，科技 +" + DEVELOP_TECH_GAIN
                + "（当前 " + player.getTechLevel() + "）。";
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "集中资源发展科技（科技 +" + DEVELOP_TECH_GAIN + "）", RARITY_PEACEFUL);
        return message;
    }

    // ==================== 科技树指令 ====================

    /**
     * 研究某个科技：按中文名找节点，前置没解锁 / 已经解锁 / 正在研究都给提示，
     * 否则把玩家文明的研究项目换成它（进度从 0 起）。
     */
    private String doResearch(Civilization player, String target) {
        if (target == null || target.isBlank()) {
            return "没听清要研究什么。可以说「研究青铜武器」「研究火药」，或者问「有什么能研究」。";
        }
        TechNode node = TechTree.getByName(target);
        if (node == null) {
            return "科技树里没有「" + target.trim() + "」。问一句「有什么能研究」可以看当前能研究的方向。";
        }
        if (player.hasUnlockedTech(node.id())) {
            return "【" + node.name() + "】已经研究过了。";
        }
        if (node.prerequisite() != null && !player.hasUnlockedTech(node.prerequisite())) {
            TechNode prerequisite = TechTree.getById(node.prerequisite());
            String prereqName = prerequisite != null ? prerequisite.name() : node.prerequisite();
            return "需要先解锁 " + prereqName + "。";
        }
        if (node.id().equals(player.getCurrentResearchId())) {
            return "正在研究【" + node.name() + "】，已积累 "
                    + Math.round(player.getResearchProgress()) + "/" + node.cost() + " 点。";
        }
        player.setCurrentResearchId(node.id());
        player.setResearchProgress(0);
        record(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "开始研究【" + node.name() + "】", RARITY_PEACEFUL);
        return "开始研究【" + node.name() + "】（需要 " + node.cost() + " 点，当前科技每年推进 "
                + formatProgress(player) + " 点）。";
    }

    /** 列出当前可研究的科技：按分支分组，标出成本与进行中的进度。 */
    private String doListResearch(Civilization player) {
        List<TechNode> available = TechTree.getAvailable(player);
        StringBuilder sb = new StringBuilder();
        if (player.getCurrentResearchId() != null) {
            TechNode current = TechTree.getById(player.getCurrentResearchId());
            if (current != null) {
                sb.append("正在研究【").append(current.name()).append("】")
                        .append(Math.round(player.getResearchProgress())).append('/')
                        .append(current.cost()).append(" 点。\n");
            }
        }
        if (available.isEmpty()) {
            return sb + "当前没有可研究的科技：能解锁的都已经解锁了。";
        }
        sb.append("当前可研究（共 ").append(available.size()).append(" 项）：\n");
        for (String branch : TechTree.branches()) {
            List<String> names = new ArrayList<>();
            for (TechNode node : available) {
                if (node.branch().equals(branch)) {
                    String mark = node.id().equals(player.getCurrentResearchId()) ? "（研究中）" : "";
                    names.add(node.name() + "（" + node.cost() + " 点）" + mark);
                }
            }
            if (!names.isEmpty()) {
                sb.append("- ").append(TechTree.branchLabel(branch)).append("：")
                        .append(String.join("、", names)).append('\n');
            }
        }
        sb.append("已解锁 ").append(player.getUnlockedTechs().size()).append(" 项。"
                + "说「研究某个科技名」即可立项。");
        return sb.toString();
    }

    /** 玩家文明每年能推进的研究进度（EvolutionEngine 的口径：techLevel × 0.1）。 */
    private String formatProgress(Civilization player) {
        return String.format("%.1f", player.getTechLevel() * 0.1);
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

        // tech → 玩家文明（int，不低于 0）
        if (effects[0] != 0) {
            long tech = (long) player.getTechLevel() + effects[0];
            player.setTechLevel((int) Math.max(0, Math.min(Integer.MAX_VALUE, tech)));
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

    /** 目标区域是否与玩家现有领土接壤。 */
    private boolean bordersPlayerTerritory(Civilization player, Region target) {
        for (Integer ownedId : player.getTerritory()) {
            if (target.getNeighbors().contains(ownedId)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家文明。 */
    private Civilization playerCivilization() {
        Civilization player = findCivilization(PLAYER_CIV_ID);
        return player != null && player.getPopulation() > 0 ? player : null;
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
                .append("格式：{\"action\":\"ask|attack|nuke|expand|develop_tech|research|list_research|free\",\"scope\":\"ALL|STRONGEST|WEAKEST|NEIGHBOR|SELF|ONE|UNKNOWN\",\"target\":\"具体名字（仅 scope=ONE 时填）\",\"reply\":\"回答内容（仅 action=ask 时填）\",\"failed\":false,\"reason\":\"仅 free 且做不到时填\",\"narrative\":\"仅 free 成功时填\",\"effects\":{\"tech\":0,\"population\":0,\"food\":0,\"resources\":0},\"newRegions\":[],\"event\":\"仅 free 成功时填\",\"pillars\":{\"science\":0,\"welfare\":0,\"resource\":0,\"military\":0},\"raw\":\"玩家原话\"}\n")
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
                .append("action：attack=出兵攻打某文明；nuke=核打击某文明；expand=占领相邻无主区域；develop_tech=发展科技；research=研究某个科技；list_research=查看可研究的科技；free=其他一切情况（默认，绝不能输出 unknown）。\n")
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
                .append("free 怎么判断能不能做——先查这张能力门槛表（拿玩家当前科技和「需要值」比）：\n")
                .append("  火 / 工具 / 部落 = 0；农业 / 文字 / 房屋 = 100；冶金 / 城市 / 长城 = 300；火药 / 大航海 = 800；\n")
                .append("  蒸汽机 / 铁路 = 2000；电力 / 汽车 = 4000；核弹 / 火箭 = 5000；卫星 / 空间站 = 7000；星际航行 / 月球基地 = 10000。\n")
                .append("  效果必须符合当前时代：石器时代飞不出地球，核子时代才可以。\n")
                .append("free 的两种输出：\n")
                .append("  1) 科技不够（做不到）→ {\"action\":\"free\",\"failed\":true,\"reason\":\"你的文明还没掌握XX技术（需要科技 N，当前 M）\"}\n")
                .append("  2) 科技够（做得到）→ {\"action\":\"free\",\"failed\":false,\"narrative\":\"一两句话描述发生了什么\",\"effects\":{\"tech\":0,\"population\":0,\"food\":0,\"resources\":0},\"newRegions\":[],\"event\":\"事件描述\"}\n")
                .append("- 效果必须有代价，不能凭空增加东西：造东西消耗资源和人口，发明消耗资源，探索消耗资源和食物。\n")
                .append("- effects：tech / population 作用在玩家文明，food / resources 作用在世界；不需要的项写 0，可以为负。\n")
                .append("- 数值上限（超出自动截断）：tech 单次 ±5000；population 单次 ±1000000；food / resources 单次 ±5000000。\n")
                .append("- newRegions：只有真的开辟了新地盘才填（例如「飞出地球」→ [\"近地轨道\",\"月球\"]），否则留空数组 []。\n")
                .append("- 明确告诉 AI：玩家说什么就用合理的代价和结果回应。不要拒绝，不要说「做不到」（只有科技不够时用 failed+reason 说明原因）。\n")
                .append("- free 例子：\n")
                .append("  · 石器时代说「飞出地球」→ {\"action\":\"free\",\"failed\":true,\"reason\":\"你的文明还没掌握火箭技术（需要科技 5000，当前 45）\"}\n")
                .append("  · 核子时代说「飞出地球」→ {\"action\":\"free\",\"failed\":false,\"narrative\":\"你的火箭冲出大气层，人类第一次进入太空。\",\"effects\":{\"tech\":500,\"population\":0,\"food\":0,\"resources\":-500000},\"newRegions\":[\"近地轨道\",\"月球\"],\"event\":\"发射火箭，挺进太空\"}\n")
                .append("  · 「建长城」（需要科技 300）→ 成功，effects.population=-50000，effects.resources=-200000，event:\"建造了长城\"\n")
                .append("  · 「发明文字」（需要科技 100）→ 成功，effects.tech=100，event:\"发明了文字\"\n")
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
        prompt.append("- 玩家正在研究：").append(describeCurrentResearch(player)).append('\n');
        prompt.append("- 玩家可研究（research 的 target 只能用这些名字）：")
                .append(describeAvailableResearch(player)).append('\n');
        prompt.append("- 世界总科技：").append(worldTech())
                .append("（核子时代门槛 ").append(NUKE_TECH_REQUIRED).append("）\n");
        prompt.append("- 存活文明总数：").append(aliveCivCountExcludingPlayer(player))
                .append("（不含玩家，人口>0）\n");
        prompt.append("- 世界总人口：").append(totalPopulation()).append('\n');
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

    /** 玩家正在研究的科技与进度，供模型回答「我在研究什么」。 */
    private String describeCurrentResearch(Civilization player) {
        TechNode current = TechTree.getById(player.getCurrentResearchId());
        if (current == null) {
            return "（无）";
        }
        return "【" + current.name() + "】" + Math.round(player.getResearchProgress()) + "/" + current.cost() + " 点";
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
                    Long found = findCivilizationByName(target.trim());
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

    /** 精确匹配优先，再包含匹配，跳过玩家和已灭亡文明。 */
    private Long findCivilizationByName(String name) {
        String wanted = name.trim();
        // 先精确匹配
        for (Civilization civ : world.getCivilizations()) {
            if (civ.getId() == PLAYER_CIV_ID) continue;
            if (civ.getPopulation() <= 0) continue;
            if (civ.getName().equals(wanted)) {
                return civ.getId();
            }
        }
        // 再包含匹配
        for (Civilization civ : world.getCivilizations()) {
            if (civ.getId() == PLAYER_CIV_ID) continue;
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

    /** 请求体。 */
    public record CommandRequest(String text) {
    }

    /** 响应体：成功给 result，失败给 error。 */
    public record CommandResult(String result, String error) {
    }
}
