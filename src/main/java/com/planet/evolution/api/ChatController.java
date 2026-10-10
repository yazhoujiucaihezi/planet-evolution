package com.planet.evolution.api;

import java.util.ArrayList;
import java.util.List;

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
        List<Long> targetCivIds;
        try {
            JsonNode node = parseJson(reply);
            action = node.path("action").asText("unknown").trim().toLowerCase();
            targetCivIds = parseCivIds(node.path("targetCivIds"));
        } catch (Exception e) {
            return new CommandResult(null, "AI 返回的内容无法解析为指令：" + e.getMessage());
        }

        String result;
        switch (action) {
            case "attack" -> result = doAttack(player, targetCivIds);
            case "develop_tech" -> result = doDevelopTech(player);
            case "expand" -> result = doExpand(player);
            case "nuke" -> result = doNuke(player, targetCivIds);
            default -> result = "无法理解这条指令，可以试试「发展科技」「扩张」「攻打某地」。";
        }
        return new CommandResult(result, null);
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

    /** 把区域划归该文明。 */
    private void annex(Civilization civilization, Region region) {
        region.setOwnerCivId((int) civilization.getId());
        civilization.addRegion(region.getId());
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
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("没有找到 JSON 对象");
        }
        return objectMapper.readTree(reply.substring(start, end + 1));
    }

    /** 构造交给 AI 的翻译 prompt：玩家原话 + 完整世界状态，让模型按意图挑出文明 id。 */
    private String buildPrompt(String text, Civilization player) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是星球演化模拟器里的指令翻译器。把玩家的一句话翻译成 JSON，只输出 JSON，不要解释，不要代码块。\n")
                .append("格式：{\"action\":\"attack|develop_tech|expand|nuke|unknown\",\"targetCivIds\":[文明id,...],\"raw\":\"玩家原话\"}\n")
                .append("action：attack=出兵攻打某文明；nuke=核打击某文明；expand=占领相邻无主区域；develop_tech=发展科技；unknown=看不懂。\n")
                .append("targetCivIds 怎么填——自己看下面的世界状态判断玩家想对哪些文明动手，不要拿玩家话里的词去硬套名字：\n")
                .append("- 「全世界」「所有人」「所有文明」→ 除玩家自己以外的全部存活文明 id\n")
                .append("- 「最强的」「最厉害的」→ 人口最多的那个文明 id\n")
                .append("- 「旁边的」「隔壁的」「邻居」→ 与玩家接壤的文明 id\n")
                .append("- 「最近的敌人」「打过我的」→ 最近一次和玩家交战的文明 id（看下方最近事件）\n")
                .append("- 玩家说了具体名字 → 世界状态里对应的那个文明 id\n")
                .append("- 判断不出来就填 []，宁可不操作也不要乱猜；expand 和 develop_tech 不用填，填 []\n")
                .append("- attack 只能打与玩家接壤的文明；nuke 需要世界总科技 ≥ 7000\n\n");
        prompt.append("世界状态：\n");
        prompt.append("- 年份：").append(world.getYear()).append('\n');
        prompt.append("- 玩家（下指令的人）：id=").append(player.getId())
                .append("，名称=").append(player.getName())
                .append("，人口=").append(player.getPopulation())
                .append("，科技=").append(player.getTechLevel()).append('\n');
        prompt.append("- 玩家领土：").append(describeRegions(player.getTerritory())).append('\n');
        prompt.append("- 可扩张的相邻无主区域：").append(describeExpandable(player)).append('\n');
        prompt.append("- 世界总科技：").append(worldTech())
                .append("（核子时代门槛 ").append(NUKE_TECH_REQUIRED).append("）\n");
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

    /** 世界总科技：各文明科技之和，口径与 EvolutionEngine.totalTech() 一致。 */
    private int worldTech() {
        return world.getCivilizations().stream().mapToInt(Civilization::getTechLevel).sum();
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

    /** 解析 targetCivIds：接受 [2,3]、单个 2 或 ["2"]，去重并丢掉非法值。 */
    private List<Long> parseCivIds(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return ids;
        }
        if (node.isNumber()) {
            long single = node.asLong();
            if (single > 0) {
                ids.add(single);
            }
            return ids;
        }
        if (!node.isArray()) {
            return ids;
        }
        for (JsonNode element : node) {
            long id;
            if (element.isNumber()) {
                id = element.asLong();
            } else if (element.isTextual()) {
                try {
                    id = Long.parseLong(element.asText().trim());
                } catch (NumberFormatException e) {
                    continue;
                }
            } else {
                continue;
            }
            if (id > 0 && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
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
