package com.planet.evolution.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.planet.evolution.dto.RegionView;
import com.planet.evolution.dto.WorldStateResponse;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Player;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Region;
import com.planet.evolution.model.World;
import com.planet.evolution.service.ClockService;
import com.planet.evolution.service.PlayerService;
import com.planet.evolution.service.SaveService;
import com.planet.evolution.service.VoteService;
import com.planet.evolution.service.WorldService;
import com.planet.evolution.simulation.EventType;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.TechTree;

/** 世界状态与时间控制接口。 */
@RestController
@RequestMapping("/api/world")
public class WorldController {

    private static final int MAX_YEARS = 10000;
    private static final int MAX_SPEED = 100;
    /** 大事模式默认搜索年限。 */
    private static final int DEFAULT_MAJOR_YEARS = 5000;
    /** 大事模式最大搜索年限，避免无限循环。 */
    private static final int MAX_MAJOR_YEARS = 50000;
    /** 跳过时代一次最多跨几个时代（与 EvolutionEngine.skipEra 的校验一致）。 */
    private static final int SKIP_ERA_MAX_STEPS = 5;
    /** 玩家文明固定为 id = 1（与 ChatController / EvolutionEngine 一致），四柱从它取。 */
    private static final long PLAYER_CIV_ID = 1L;
    /** 分配文明 id 时的向上扫描上限（正常远用不到，兜底防死循环）。 */
    private static final int MAX_CIV_ID_SCAN = 10000;

    /**
     * sessionId 认不出玩家时的统一提示。后端重启会清空内存里的玩家表（sessionId 只存在内存里），
     * 于是浏览器 localStorage 里的旧 sessionId 就"失效"了。前端收到这句话要清掉本地会话、重新加入。
     */
    private static final String SESSION_EXPIRED_MESSAGE = "登录已失效，请刷新页面重新加入";
    /** 玩家指令事件的稀有度（与 ChatController.RARITY_PEACEFUL 一致）。 */
    private static final int RARITY_PEACEFUL = 50;

    private final WorldService worldService;
    private final ClockService clockService;
    private final World world;
    private final EvolutionEngine engine;
    private final SaveService saveService;
    private final WorldMapper worldMapper;
    private final PlayerService playerService;
    private final VoteService voteService;

    public WorldController(WorldService worldService, ClockService clockService, World world,
            EvolutionEngine engine, SaveService saveService, WorldMapper worldMapper,
            PlayerService playerService, VoteService voteService) {
        this.worldService = worldService;
        this.clockService = clockService;
        this.world = world;
        this.engine = engine;
        this.saveService = saveService;
        this.worldMapper = worldMapper;
        this.playerService = playerService;
        this.voteService = voteService;
    }

    /**
     * 世界状态。
     *
     * @param sessionId 可选：带上就按「该玩家当前控制的文明」返回四柱 / 概览；
     *                  不带则退回文明 id=1（兼容旧前端与探活脚本）
     */
    @GetMapping
    public WorldStateResponse getWorld(@RequestParam(required = false) String sessionId) {
        Planet planet = world.getPlanet();

        List<WorldStateResponse.CivilizationView> civilizations = new ArrayList<>();
        long population = 0;
        int techTotal = 0;
        for (Civilization civilization : world.getCivilizations()) {
            population += civilization.getPopulation();
            techTotal += civilization.getTechLevel();
            civilizations.add(new WorldStateResponse.CivilizationView(civilization.getId(),
                    civilization.getName(), civilization.getFoundedYear(), civilization.getPopulation(),
                    civilization.getTechLevel()));
        }
        int year = world.getYear();
        // civilizations 仍返回全表（含灭亡文明）；两个计数为存活/累计口径
        WorldStateResponse.WorldState state = new WorldStateResponse.WorldState(year, planet.getName(),
                planet.getTemperature(), planet.getHabitability(), engine.getFood(), engine.getResources(),
                population, techTotal, world.aliveCivilizationCount(), world.extinctCivilizationCount(),
                engine.currentEra());
        // 四柱属于「该玩家当前控制的文明」：不存在或已灭亡时给 null，前端留空
        WorldStateResponse.Pillars pillars = null;
        Civilization player = playerCivilization(sessionId);
        if (player != null && player.getPopulation() > 0) {
            pillars = new WorldStateResponse.Pillars(player.getScience(), player.getWelfare(),
                    player.getResource(), player.getMilitary());
        }
        // 玩家概览：五种资源点 / 四个状态指标 / 个人时代（国家不存在时为 null）
        WorldStateResponse.PlayerView playerView = null;
        if (player != null) {
            playerView = new WorldStateResponse.PlayerView(player.getId(), player.getName(),
                    player.getFoundedYear(), player.getPopulation(), player.getTechLevel(),
                    player.currentEra(), player.getEraIndex(),
                    pointsOf(player), indicatorsOf(player));
        }
        // 终局判定：完全交给引擎（引擎按「玩家当前控制的文明」判定，灭亡与 1000 年上限都在 tick 里判完）。
        // 这里不再兜一层写死 id=1 的逻辑——否则玩家切换到别的文明后会立刻被误判为终局。
        boolean gameOver = engine.isGameOver();
        String gameOverReason = engine.getGameOverReason();
        return new WorldStateResponse(state, clockService.currentSpeed(), clockService.isPaused(), pillars,
                playerView, gameOver, gameOverReason, civilizations);
    }

    /**
     * 取当前请求玩家所控制的文明。
     *
     * <p>带了 sessionId 且能认出玩家：只认它当前控制的文明（还没分配 / 该文明不存在 → null），
     * 绝不落到别人的文明上（否则多玩家时看起来就是「文明重合」）。
     * 没带 sessionId 或认不出该玩家：退回文明 id=1，兼容旧调用。
     */
    private Civilization playerCivilization(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Player player = playerService.get(sessionId);
            if (player != null) {
                int civId = player.getControlledCivId();
                if (civId <= 0) {
                    return null;   // 已登录但还没分到独立文明
                }
                return world.findCivilization(civId);
            }
        }
        return world.findCivilization(PLAYER_CIV_ID);
    }

    // ==================== 玩家接口 ====================

    /**
     * 加入游戏：body {"name":"张三"} →
     * 返回 {"sessionId":"xxx","civId":1,"civName":"人类","aliveCivCount":1}。
     *
     * <p><b>civId &gt; 0</b>：分到了一个独立且有效的文明（没被别人控制、也不是灭亡文明），直接用。
     * <b>civId = 0</b>：当前没有可分给它的独立文明（比如世界里只有 1 个文明且已被别人控制），
     * 前端据此提示玩家「和朋友共用一个文明」或「等新文明诞生后再切换」。civName 为 null。
     */
    @PostMapping("/player/join")
    public JoinResult join(@RequestBody(required = false) JoinRequest request) {
        String name = request == null || request.name() == null ? "匿名" : request.name();
        Player player = playerService.join(name);
        // 从 1 开始扫：跳过已被别人控制的、已灭亡的文明（见 allocateCivId）
        int candidate = allocateCivId(player.getSessionId());
        Civilization assigned = candidate == 0 ? null : world.findCivilization(candidate);
        boolean valid = assigned != null && assigned.getPopulation() > 0;
        int civId = valid ? candidate : 0;
        player.setControlledCivId(civId);
        return new JoinResult(player.getSessionId(), civId, valid ? assigned.getName() : null,
                world.aliveCivilizationCount());
    }

    /**
     * 从 1 开始找第一个可分配的文明 id：
     * 跳过已被其他在线玩家控制的文明，也跳过已经灭亡的文明；该 id 尚无文明对象时照样返回，
     * 由 {@link #join} 判定它是不是「有效文明」。
     */
    private int allocateCivId(String sessionId) {
        for (int civId = 1; civId <= MAX_CIV_ID_SCAN; civId++) {
            if (playerService.isControlledByOther(civId, sessionId)) {
                continue;   // 已被别的玩家控制
            }
            Civilization existing = world.findCivilization(civId);
            if (existing != null && existing.getPopulation() <= 0) {
                continue;   // 已灭亡
            }
            return civId;
        }
        // 兜底：扫描区间外往后顺延，绝不返回已被占用的 id
        int fallback = MAX_CIV_ID_SCAN + 1;
        while (playerService.isControlledByOther(fallback, sessionId)) {
            fallback++;
        }
        return fallback;
    }

    /**
     * 切换控制的文明。sessionId 走 query 参数（与其它需要身份的接口保持一致）。
     * body {"civId":3}
     */
    @PostMapping("/player/choose-civ")
    public ApiResult chooseCiv(@RequestBody(required = false) ChooseCivRequest request,
                               @RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ApiResult(false, SESSION_EXPIRED_MESSAGE);
        }
        playerService.touch(sessionId);
        Player player = playerService.get(sessionId);
        if (player == null) {
            // 后端重启过 / 会话太久没心跳：内存里已经没有这个 session
            return new ApiResult(false, SESSION_EXPIRED_MESSAGE);
        }
        if (request == null || request.civId() <= 0) {
            return new ApiResult(false, "请提供 civId");
        }
        // 只允许切到「真实存在且存活」的文明：不存在的 id 一律拒绝，否则玩家会控制一个幻影文明
        Civilization target = world.findCivilization(request.civId());
        if (target == null) {
            return new ApiResult(false, "文明 " + request.civId() + " 不存在");
        }
        if (target.getPopulation() <= 0) {
            return new ApiResult(false, "文明 " + request.civId() + " 已灭亡，无法切换");
        }
        // 只改玩家当前控制的文明，不触发任何终局判定（终局由引擎按「当前控制的文明」重新求值）
        player.setControlledCivId(request.civId());
        return new ApiResult(true, "已切换到文明 " + request.civId());
    }

    /** 列出在线玩家。 */
    @GetMapping("/player/list")
    public List<PlayerInfo> listPlayers() {
        List<PlayerInfo> result = new ArrayList<>();
        for (Player p : playerService.listOnline()) {
            String civName = null;
            Civilization civ = world.findCivilization(p.getControlledCivId());
            if (civ != null) {
                civName = civ.getName();
            }
            result.add(new PlayerInfo(p.getSessionId(), p.getName(), p.getControlledCivId(), civName));
        }
        return result;
    }

    // ==================== 同步接口 ====================

    /**
     * 快速同步：前端每秒调一次。
     *
     * <p>{@code sessionValid} 告诉前端「你带的 sessionId 后端还认不认得」——后端重启会清空内存里的
     * 玩家表，旧 sessionId 就认不出了；前端据此清掉本地会话、重新加入。
     * 压根没带 sessionId（还没登录）不算失效，免得一进页面就弹重新登录。
     */
    @GetMapping("/sync")
    public SyncResult sync(@RequestParam(required = false) Long since,
                          @RequestParam(required = false) String sessionId) {
        boolean loggedIn = sessionId != null && !sessionId.isBlank();
        boolean sessionValid = !loggedIn || playerService.get(sessionId) != null;
        // 更新玩家心跳
        if (loggedIn) {
            playerService.touch(sessionId);
        }
        int eventCount = worldMapper.selectEvents().size();
        int playerCount = playerService.listOnline().size();
        // 区域归属映射
        Map<Integer, Integer> regionOwners = new LinkedHashMap<>();
        for (Region region : world.getRegionMap().all()) {
            regionOwners.put(region.getId(), region.getOwnerCivId());
        }
        return new SyncResult(System.currentTimeMillis(), world.getYear(),
                engine.isGameOver(), eventCount, playerCount, regionOwners, sessionValid);
    }

    // ==================== 投票接口 ====================

    /** 发起投票（sessionId 走 query 参数）：body {"type":"skip_era","targetEraIndex":7} */
    @PostMapping("/vote/propose")
    public VoteResult propose(@RequestBody(required = false) ProposeRequest request,
                             @RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank() || playerService.get(sessionId) == null) {
            return new VoteResult(false, SESSION_EXPIRED_MESSAGE, null);
        }
        playerService.touch(sessionId);
        if (request == null) {
            return new VoteResult(false, "请提供请求体", null);
        }
        VoteService.ProposeResult proposed =
                voteService.propose(request.type(), request.targetEraIndex(), sessionId);
        switch (proposed.outcome()) {
            // 单人：没有投票，已经直接执行完了
            case EXECUTED -> {
                return new VoteResult(true, proposed.message(), null);
            }
            // 单人尝试执行但被规则拒绝（如时代下标非法）
            case FAILED -> {
                return new VoteResult(false, proposed.message(), null);
            }
            case BUSY -> {
                return new VoteResult(false, proposed.message(), null);
            }
            default -> {
                return new VoteResult(true, proposed.message(), voteService.status());
            }
        }
    }

    /** 同意投票（sessionId 走 query 参数，与 propose 一致）。 */
    @PostMapping("/vote/agree")
    public VoteResult agree(@RequestParam(required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank() || playerService.get(sessionId) == null) {
            return new VoteResult(false, SESSION_EXPIRED_MESSAGE, null);
        }
        playerService.touch(sessionId);
        if (!voteService.hasVote()) {
            return new VoteResult(false, "当前没有投票", null);
        }
        boolean passed = voteService.agree(sessionId);
        VoteService.VoteStatus status = voteService.status();
        if (passed) {
            // 执行投票
            executeVote();
            return new VoteResult(true, "投票通过！", status);
        }
        return new VoteResult(true, "已同意", status);
    }

    /** 投票状态。 */
    @GetMapping("/vote/status")
    public VoteService.VoteStatus voteStatus() {
        return voteService.status();
    }

    private void executeVote() {
        VoteService.Vote vote = voteService.getCurrentVote();
        if (vote == null) return;
        if ("skip_era".equals(vote.type())) {
            engine.skipEra(vote.targetValue());
        } else if ("speed".equals(vote.type())) {
            clockService.setSpeed(vote.targetValue());
        }
    }

    // ==================== DTO ====================

    /**
     * @param civId         分到的文明 id；0 表示当前没有独立文明可分（让前端提示共用一个或等新文明）
     * @param civName       分到的文明名；civId 为 0 时是 null
     * @param aliveCivCount 当前存活文明数（前端用来写「当前只有 N 个文明」）
     */
    public record JoinResult(String sessionId, int civId, String civName, int aliveCivCount) {}
    public record JoinRequest(String name) {}
    public record ChooseCivRequest(int civId) {}
    public record ApiResult(boolean ok, String message) {}
    public record PlayerInfo(String sessionId, String name, int civId, String civName) {}
    public record SyncResult(long serverTime, int year, boolean gameOver,
                            int eventCount, int playerCount,
                            Map<Integer, Integer> regionOwners, boolean sessionValid) {}
    public record ProposeRequest(String type, int targetEraIndex) {}
    public record VoteResult(boolean ok, String message, VoteService.VoteStatus status) {}

    @GetMapping("/events")
    public List<GameEvent> getEvents() {
        return worldService.listEvents();
    }

    /**
     * 所有区域及其归属。ownerCivId 为 0 表示无主，ownerCivName 为 null。
     * explored 为 false 表示玩家尚未探索到（前端显示为黑雾）。
     */
    @GetMapping("/regions")
    public List<RegionView> getRegions() {
        List<RegionView> views = new ArrayList<>();
        for (Region region : world.getRegionMap().all()) {
            int ownerId = region.getOwnerCivId();
            String ownerName = null;
            if (ownerId != Region.UNOWNED) {
                for (Civilization civilization : world.getCivilizations()) {
                    if (civilization.getId() == ownerId) {
                        ownerName = civilization.getName();
                        break;
                    }
                }
            }
            views.add(new RegionView(region.getId(), region.getName(), ownerId, ownerName,
                    region.isExplored(), region.getTerrain()));
        }
        return views;
    }

    /**
     * 区域详情：返回指定区域的完整信息。
     * 无主时 ownerCivId=0，ownerStats 为 null。
     * 未探索区域也返回详情（前端判断是否可点击）。
     */
    @GetMapping("/regions/{id}/detail")
    public RegionDetail getRegionDetail(@PathVariable int id) {
        Region region = world.getRegionMap().get(id);
        if (region == null) {
            return null;
        }

        int ownerId = region.getOwnerCivId();
        String ownerName = null;
        CivStats ownerStats = null;
        if (ownerId != Region.UNOWNED) {
            for (Civilization civilization : world.getCivilizations()) {
                if (civilization.getId() == ownerId) {
                    ownerName = civilization.getName();
                    ownerStats = new CivStats(civilization.getPopulation(), civilization.getTechLevel(),
                            civilization.getTerritory().size());
                    break;
                }
            }
        }

        // 邻接区域名称列表
        List<String> neighborNames = new ArrayList<>();
        for (int neighborId : region.getNeighbors()) {
            Region neighbor = world.getRegionMap().get(neighborId);
            if (neighbor != null) {
                neighborNames.add(neighbor.getName());
            }
        }

        // 筛选与该区域相关的事件（描述中包含区域名）
        String regionName = region.getName();
        Pattern pattern = Pattern.compile(Pattern.quote(regionName));
        List<GameEvent> allEvents = worldService.listEvents();
        List<GameEvent> relatedEvents = allEvents.stream()
                .filter(e -> pattern.matcher(e.description()).find())
                .sorted(Comparator.comparingInt(GameEvent::year).reversed())
                .limit(10)
                .toList();

        List<EventView> recentEvents = new ArrayList<>();
        for (GameEvent event : relatedEvents) {
            recentEvents.add(new EventView(event.year(), event.type().name(), event.description()));
        }

        return new RegionDetail(region.getId(), region.getName(), ownerId, ownerName,
                neighborNames, region.isExplored(), ownerStats, recentEvents);
    }

    /** 区域详情响应 */
    public record RegionDetail(
            int id,
            String name,
            int ownerCivId,
            String ownerCivName,
            List<String> neighbors,
            boolean explored,
            CivStats ownerStats,
            List<EventView> recentEvents
    ) {}

    /** 文明统计 */
    public record CivStats(long population, int techLevel, int territoryCount) {}

    /** 事件视图 */
    public record EventView(int year, String type, String description) {}

    /**
     * 科技树只读视图：节点定义全部来自引擎的 {@link TechTree}（4 条分支 × 8 个节点），
     * 解锁/在研状态全部来自玩家文明自己的研究记录，本类不再自带任何科技树定义。
     *
     * <p>这里只做「读 + 按分支分组」，不碰任何研究逻辑。
     */
    @GetMapping("/tech-tree")
    public TechTreeResponse getTechTree() {
        Civilization player = world.findCivilization(PLAYER_CIV_ID);
        // 玩家不存在时按「什么都没解锁、资源点全 0」处理，面板仍能展示完整科技树结构
        List<String> playerUnlocked = player == null
                ? new ArrayList<>()
                : new ArrayList<>(player.getUnlockedTechs());
        Map<String, Integer> playerPoints = player == null
                ? new LinkedHashMap<>()
                : pointsOf(player);
        int playerEraIndex = player == null ? 0 : player.getEraIndex();
        String playerEra = player == null ? Civilization.ERAS[0] : player.currentEra();

        Map<String, List<TechNode>> branches = new LinkedHashMap<>();
        // 用 var 是为了和下面同名的响应 DTO TechNode 区分开（engine 的节点是 simulation.TechNode）
        for (var node : TechTree.all()) {
            boolean unlocked = player != null && player.hasUnlockedTech(node.id());
            // 实际消耗：同组已选过别的节点时每种资源 ×2（前端据此显示"同组已选，×2"）
            Map<String, Integer> costs = node.costs() == null ? Map.of() : node.costs();
            Map<String, Integer> actualCosts = TechTree.actualCosts(node, player);
            boolean penalized = TechTree.isPenalized(node, player);
            branches.computeIfAbsent(node.branch(), key -> new ArrayList<>())
                    .add(new TechNode(node.id(), node.name(), node.prerequisite(), costs, actualCosts,
                            node.cost(), unlocked, penalized));
        }
        return new TechTreeResponse(branches, playerUnlocked, playerPoints, playerEraIndex, playerEra);
    }

    /** 玩家五种资源点（键为 food / production / military / culture / science）。 */
    private static Map<String, Integer> pointsOf(Civilization civilization) {
        return civilization.pointMap();
    }

    /** 玩家四个状态指标（键为 unrest / culture / prestige / tension）。 */
    private static Map<String, Integer> indicatorsOf(Civilization civilization) {
        Map<String, Integer> indicators = new LinkedHashMap<>();
        indicators.put("unrest", civilization.getUnrest());
        indicators.put("culture", civilization.getCulture());
        indicators.put("prestige", civilization.getPrestige());
        indicators.put("tension", civilization.getTension());
        return indicators;
    }

    /**
     * 直接研究某个科技节点（科技树面板专用，不走 AI 翻译）：
     * 前端点节点 → body {"techId":"mil_gunpowder"} → 当场扣点、解锁、加科技、写事件。
     *
     * <p>校验顺序与 ChatController.doResearch 保持一致：节点存在 → 未解锁 → 前置满足 → 科技点足够（按实际成本，
     * 即同组已选时 ×2）。互斥组不再硬性拦人，只加价。成功返回 result，任一校验失败返回 error。
     */
    @PostMapping("/research")
    public ResearchResult research(@RequestBody(required = false) ResearchRequest request) {
        String techId = request == null ? null : request.techId();
        if (techId == null || techId.isBlank()) {
            return new ResearchResult(null, "请提供 techId");
        }
        Civilization player = world.findCivilization(PLAYER_CIV_ID);
        if (player == null || player.getPopulation() <= 0) {
            return new ResearchResult(null, "玩家文明不存在或已灭亡");
        }
        com.planet.evolution.simulation.TechNode node = TechTree.getById(techId.trim());
        if (node == null) {
            return new ResearchResult(null, "科技树里没有这个科技：" + techId);
        }
        if (player.hasUnlockedTech(node.id())) {
            return new ResearchResult(null, "已经研究过了");
        }
        if (node.prerequisite() != null && !player.hasUnlockedTech(node.prerequisite())) {
            com.planet.evolution.simulation.TechNode prerequisite = TechTree.getById(node.prerequisite());
            String prereqName = prerequisite != null ? prerequisite.name() : node.prerequisite();
            return new ResearchResult(null, "需要先解锁【" + prereqName + "】");
        }
        // 实际消耗：同组已选过别的节点时每种资源 ×2
        Map<String, Integer> costs = TechTree.actualCosts(node, player);
        if (!TechTree.canAfford(player, costs)) {
            return new ResearchResult(null, "资源点不够（需要 " + TechTree.costText(costs) + "）");
        }
        // 立即执行：扣点 → 解锁（顺带把对应产出系数 ×1.2）→ 科技 +techBonus
        TechTree.pay(player, costs);
        TechTree.unlock(player, node);
        player.setTechLevel(player.getTechLevel() + node.techBonus());
        worldMapper.insertEvent(new GameEvent(world.getYear(), EventType.PLAYER_COMMAND,
                player.getName() + "解锁了【" + node.name() + "】", RARITY_PEACEFUL));
        return new ResearchResult(
                "已解锁【" + node.name() + "】，剩余 " + TechTree.costText(pointsOf(player)), null);
    }

    @PostMapping("/advance")
    public AdvanceResult advance(@RequestBody AdvanceRequest request) {
        int years = Math.min(MAX_YEARS, Math.max(1, request.years()));
        worldService.advance(years);
        return new AdvanceResult(world.getYear(), years);
    }

    /** 大事模式：持续推进直到下一个重大事件，或达到最大搜索年限。 */
    @PostMapping("/run-to-major")
    public MajorResult runToMajor(@RequestBody(required = false) RunToMajorRequest request) {
        int maxYears = request == null ? DEFAULT_MAJOR_YEARS
                : Math.min(MAX_MAJOR_YEARS, Math.max(1, request.maxYears()));
        return MajorResult.of(worldService.advanceToNextMajorEvent(maxYears));
    }

    /** 跳过时代用：当前时代 + 可选的目标时代（最多跨 SKIP_ERA_MAX_STEPS 个）。 */
    @GetMapping("/eras")
    public EraOptions eras() {
        int current = engine.currentEraIndex();
        String[] names = engine.eras();
        List<EraOption> targets = new ArrayList<>();
        int last = Math.min(current + SKIP_ERA_MAX_STEPS, names.length - 1);
        for (int i = current + 1; i <= last; i++) {
            targets.add(new EraOption(i, names[i]));
        }
        return new EraOptions(current, engine.currentEra(), List.of(names), targets);
    }

    /**
     * 跳过当前时代：单人直接执行，不投票。
     * 成功返回 result，校验失败返回 error。
     */
    @PostMapping("/skip-era")
    public SkipEraResult skipEra(@RequestBody(required = false) SkipEraRequest request) {
        if (request == null) {
            return new SkipEraResult(null, "请提供 targetEraIndex");
        }
        int before = engine.currentEraIndex();
        String message = engine.skipEra(request.targetEraIndex());
        // 时代确实推进了才算成功；否则 message 是 skipEra 给出的校验失败原因
        return engine.currentEraIndex() > before
                ? new SkipEraResult(message, null)
                : new SkipEraResult(null, message);
    }

    /** 新游戏：清空世界从零开始，并立刻覆盖存档文件。 */
    @PostMapping("/new-game")
    public NewGameResult newGame() {
        String message = engine.resetWorld();
        // 存档写失败不影响「世界已重置」，把失败原因带回给前端
        String saveError = saveService == null ? null : saveService.saveWithResult();
        return new NewGameResult(message, saveError);
    }

    /**
     * 新游戏（按所选开局时代）。body 形如 {"startEra":"ancient"}。
     * 开局 key：savage / agrarian / ancient / medieval / industrial / information / interstellar。
     */
    @PostMapping("/new-game-with-era")
    public NewGameResult newGameWithEra(@RequestBody(required = false) NewGameWithEraRequest request) {
        String key = request == null ? null : request.startEra();
        String message = engine.newGameWithEra(key);
        if (message == null) {
            return new NewGameResult(null, "未知的开局：" + key);
        }
        String saveError = saveService == null ? null : saveService.saveWithResult();
        return new NewGameResult(message, saveError);
    }

    /**
     * 科技树研究模式：active = 玩家文明不自动研究、等玩家指令；passive = 玩家文明自动研究。
     * AI 文明两种模式下都自动研究。
     */
    @GetMapping("/research-mode")
    public ResearchModeResult researchMode() {
        return new ResearchModeResult(engine.getResearchMode(), null);
    }

    /**
     * 切换研究模式，body 形如 {"mode":"active"}。
     * 切换成功后立刻存一次盘，重启后模式不变。
     */
    @PostMapping("/research-mode")
    public ResearchModeResult setResearchMode(@RequestBody(required = false) ResearchModeRequest request) {
        String mode = request == null ? null : request.mode();
        if (mode == null || mode.isBlank()) {
            return new ResearchModeResult(engine.getResearchMode(), "请提供 mode（active 或 passive）");
        }
        if (!engine.setResearchMode(mode)) {
            return new ResearchModeResult(engine.getResearchMode(), "模式只能是 active 或 passive");
        }
        // 模式已经生效；存档失败只作为提示返回，不回滚模式
        String saveError = saveService == null ? null : saveService.saveWithResult();
        return new ResearchModeResult(engine.getResearchMode(), saveError);
    }

    /**
     * AI 参与度：normal = 每 20 年驱动一次世界；high = 每 10 年一次且参与国家更多。
     * 默认 normal。两种档位都保持「一次 AI 调用最多 5 个国家」。
     */
    @GetMapping("/ai-level")
    public AiLevelResult aiLevel() {
        return new AiLevelResult(engine.getAiLevel(), null);
    }

    /**
     * 切换 AI 参与度，body 形如 {"level":"high"}。
     * 正常返回生效后的档位；入参非法时 error 非空、档位保持不变。
     */
    @PostMapping("/ai-level")
    public AiLevelResult setAiLevel(@RequestBody(required = false) AiLevelRequest request) {
        String level = request == null ? null : request.level();
        if (level == null || level.isBlank()) {
            return new AiLevelResult(engine.getAiLevel(), "请提供 level（normal 或 high）");
        }
        if (!engine.setAiLevel(level)) {
            return new AiLevelResult(engine.getAiLevel(), "level 只能是 normal 或 high");
        }
        return new AiLevelResult(engine.getAiLevel(), null);
    }

    /** AI 参与度请求体：level 取 normal / high。 */
    public record AiLevelRequest(String level) {
    }

    /** AI 参与度响应：level 是生效后的档位；error 非空表示入参被拒。 */
    public record AiLevelResult(String level, String error) {
    }

    @PostMapping("/pause")
    public ClockResult pause() {
        clockService.pause();
        return clockResult();
    }

    @PostMapping("/resume")
    public ClockResult resume() {
        clockService.resume();
        return clockResult();
    }

    @PostMapping("/speed")
    public ClockResult speed(@RequestBody SpeedRequest request) {
        clockService.setSpeed(Math.min(MAX_SPEED, Math.max(1, request.speed())));
        return clockResult();
    }

    /** 手动保存：把当前世界状态写入存档文件。 */
    @PostMapping("/save")
    public SaveResult save() {
        String error = saveService.saveWithResult();
        return error == null ? new SaveResult(true, null) : new SaveResult(false, error);
    }

    /**
     * 存档概要：存档时间 / 年份 / 国家数。
     * 存档不存在时 exists=false、year 与 civilizationCount 为 -1。
     */
    @GetMapping("/save-info")
    public SaveService.SaveInfo saveInfo() {
        return saveService.saveInfo();
    }

    private ClockResult clockResult() {
        return new ClockResult(clockService.isPaused(), clockService.currentSpeed());
    }

    public record SaveResult(boolean saved, String error) {
    }

    public record AdvanceRequest(int years) {
    }

    public record AdvanceResult(int year, int advancedYears) {
    }

    public record SpeedRequest(int speed) {
    }

    public record SkipEraRequest(int targetEraIndex) {
    }

    /** 新游戏：result 是重置结果；存档写入失败时 error 非空（世界已经重置）。 */
    public record NewGameResult(String result, String error) {
    }

    /** 按开局时代重开：startEra 是开局 key。 */
    public record NewGameWithEraRequest(String startEra) {
    }

    /** 研究模式请求体：mode 取 active / passive。 */
    public record ResearchModeRequest(String mode) {
    }

    /** 研究模式响应：mode 是生效后的模式；error 非空表示入参被拒或存档写失败。 */
    public record ResearchModeResult(String mode, String error) {
    }

    /** 跳过时代：成功给 result，校验失败给 error，另一个字段为 null。 */
    public record SkipEraResult(String result, String error) {
    }

    /** 前端选时代用的一项：下标 + 名称。 */
    public record EraOption(int index, String name) {
    }

    public record EraOptions(int currentEraIndex, String currentEra, List<String> eras, List<EraOption> targets) {
    }

    public record RunToMajorRequest(int maxYears) {
    }

    public record MajorResult(int year, int advancedYears, boolean found, boolean truncated, String message,
            GameEvent event) {

        static MajorResult of(WorldService.MajorRunResult result) {
            String message = result.found()
                    ? "遇到重大事件，已暂停：" + result.event().type().getLabel()
                    : "达到最大搜索年限（" + result.advancedYears() + " 年）仍未出现重大事件";
            return new MajorResult(result.year(), result.advancedYears(), result.found(), result.truncated(),
                    message, result.event());
        }
    }

    public record ClockResult(boolean paused, int speed) {
    }

    // ==================== 科技树 ====================

    /**
     * 科技树里一个节点（对外视图）。prerequisite 为 null 表示这是树根。
     * costs 是基础消耗（按资源类型，如 {"science":100,"production":50}）；
     * actualCosts 是实际消耗（同组已选过别的节点时每种资源 ×2）；totalCost 是实际消耗之和，便于排序；
     * penalized 标记是否正被同组惩罚，前端据此显示「（同组已选，×2）」。
     */
    public record TechNode(String id, String name, String prerequisite, Map<String, Integer> costs,
            Map<String, Integer> actualCosts, int totalCost, boolean unlocked, boolean penalized) {
    }

    /**
     * 科技树响应。branches 按分支 key 分组；playerPoints 是玩家当前五种资源点；
     * playerEraIndex/Era 是玩家个人时代。
     */
    public record TechTreeResponse(Map<String, List<TechNode>> branches, List<String> playerUnlocked,
            Map<String, Integer> playerPoints, int playerEraIndex, String playerEra) {
    }

    /** 直接研究请求体：techId 是科技树节点的 id。 */
    public record ResearchRequest(String techId) {
    }

    /** 直接研究响应：成功给 result，校验失败给 error，另一个字段为 null。 */
    public record ResearchResult(String result, String error) {
    }

    /**
     * 兼容别名控制器：只转发 {@code /api/vote/status}。
     *
     * <p>历史前端调的是 {@code /api/vote/status}（少了 {@code /world} 这段前缀），而本类的类级前缀是
     * {@code /api/world}，没法直接暴露那个路径，于是 404。规范路径是
     * {@code /api/world/vote/status}（前端已改用它）；这里额外保留一个只读别名，
     * 让旧页面 / 旧缓存里的 front-end 也能拿到状态，不至于刷一排 404。
     */
    @RestController
    @RequestMapping("/api/vote")
    public static class VoteCompatController {

        private final VoteService voteService;

        VoteCompatController(VoteService voteService) {
            this.voteService = voteService;
        }

        /** 没有进行中的投票时返回 200 + 空 body（与规范路径一致）。 */
        @GetMapping("/status")
        public VoteService.VoteStatus status() {
            return voteService.status();
        }
    }
}
