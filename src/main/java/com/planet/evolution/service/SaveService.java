package com.planet.evolution.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Memory;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Region;
import com.planet.evolution.model.Seed;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 世界存档：把世界状态写成 JSON，启动时自动恢复。
 * 存读对称——存档里有什么，恢复时就还原什么。
 * 所有异常都在内部消化，绝不因为存档问题让服务崩溃。
 */
@Service
public class SaveService {

    private static final Logger log = LoggerFactory.getLogger(SaveService.class);

    /** 存档路径（相对进程工作目录）。 */
    private static final String SAVE_PATH = "logs/world-save.json";
    /** 写盘用的临时文件：落盘成功后再原子替换主存档，避免留下写了一半的存档。 */
    private static final String SAVE_TMP_PATH = SAVE_PATH + ".tmp";
    /** 坏档备份路径。 */
    private static final String SAVE_BAK_PATH = SAVE_PATH + ".bak";

    /** 存档时间展示格式。 */
    private static final DateTimeFormatter SAVE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 玩家文明固定为 id = 1（与 ChatController / EvolutionEngine 一致），旧存档缺探索字段时按它重算。 */
    private static final long PLAYER_CIV_ID = 1L;

    private final World world;
    private final EvolutionEngine engine;
    private final WorldMapper worldMapper;
    private final ObjectMapper objectMapper;

    public SaveService(World world, EvolutionEngine engine, WorldMapper worldMapper,
            ObjectMapper objectMapper) {
        this.world = world;
        this.engine = engine;
        this.worldMapper = worldMapper;
        this.objectMapper = objectMapper;
    }

    /** 应用启动时：有存档就恢复，没有就沿用全新世界。 */
    @PostConstruct
    public void loadOnStartup() {
        // 上次写盘写到一半就崩了：残留的 .tmp 一律丢掉，主存档才是可信的
        discardLeftoverTemp();
        File file = new File(SAVE_PATH);
        if (!file.isFile()) {
            log.info("未找到存档 {}，使用全新世界", SAVE_PATH);
            return;
        }
        try {
            WorldSave save = objectMapper.readValue(file, WorldSave.class);
            restore(save);
            log.info("已从 {} 恢复世界：年份 {}，文明 {} 个，事件 {} 条", SAVE_PATH, world.getYear(),
                    world.getCivilizations().size(), worldMapper.selectEvents().size());
        } catch (Exception e) {
            // 存档损坏也不能阻止启动：先把坏档备份出来留证，再从空世界（= 一局新游戏）开始
            log.error("读取存档失败（坏档），将备份为 {} 并从空世界启动：{}", SAVE_BAK_PATH, e.getMessage(), e);
            backupCorruptSave(file);
            resetToFreshWorld();
        }
    }

    /** 丢掉上次中断留下的临时存档（存在即说明上次写盘没走完）。 */
    private void discardLeftoverTemp() {
        File tmp = new File(SAVE_TMP_PATH);
        if (!tmp.isFile()) {
            return;
        }
        if (tmp.delete()) {
            log.warn("发现上次中断残留的临时存档 {}，已丢弃，改用主存档", SAVE_TMP_PATH);
        } else {
            log.warn("临时存档 {} 删除失败，已忽略（绝不会用它恢复世界）", SAVE_TMP_PATH);
        }
    }

    /** 把坏掉的存档改名成 .bak；已有同名备份时覆盖它。 */
    private void backupCorruptSave(File file) {
        File bak = new File(SAVE_BAK_PATH);
        if (bak.isFile() && !bak.delete()) {
            log.warn("旧备份 {} 删除失败，尝试直接覆盖", SAVE_BAK_PATH);
        }
        if (file.renameTo(bak)) {
            log.warn("坏档已备份到 {}", SAVE_BAK_PATH);
        } else {
            log.warn("坏档备份失败，原文件仍在 {}", file.getAbsolutePath());
        }
    }

    /** 从空世界启动：等同于一局新游戏（清文明 / 事件 / 种子，重开玩家文明）。 */
    private void resetToFreshWorld() {
        try {
            engine.resetWorld();
            log.info("已按空世界启动：年份 {}，文明 {} 个", world.getYear(), world.getCivilizations().size());
        } catch (Exception e) {
            log.error("空世界初始化失败，继续使用当前内存中的世界：{}", e.getMessage(), e);
        }
    }

    /** 保存当前世界快照；失败只记日志。 */
    public void save() {
        saveWithResult();
    }

    /**
     * 保存当前世界快照，并把结果返回给调用方（手动保存接口用）。
     *
     * @return 成功返回 null；失败返回错误信息
     */
    public String saveWithResult() {
        File tmp = new File(SAVE_TMP_PATH);
        try {
            WorldSave save = snapshot();
            File file = new File(SAVE_PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log.warn("无法创建存档目录 {}", parent.getAbsolutePath());
            }
            // 先写 .tmp，落盘成功后再原子替换主存档：中途崩溃不会留下半截主存档
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, save);
            replaceFile(tmp, file);
            log.info("世界已存档到 {}（年份 {}，文明 {} 个，事件 {} 条）", SAVE_PATH, save.year(),
                    save.civilizations().size(), save.events().size());
            return null;
        } catch (Exception e) {
            // 失败时别把半截临时文件留在磁盘上，否则下次启动会被当成"中断残留"
            if (tmp.isFile() && !tmp.delete()) {
                log.warn("临时存档 {} 清理失败", SAVE_TMP_PATH);
            }
            log.error("写入存档失败，已忽略：{}", e.getMessage(), e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** 用临时文件替换主存档；跨设备或被占用时退回「删旧 + 改名」。 */
    private static void replaceFile(File tmp, File target) throws IOException {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            if (target.exists() && !target.delete()) {
                throw moveFailed;
            }
            if (!tmp.renameTo(target)) {
                throw moveFailed;
            }
        }
    }

    /**
     * 存档概要：存档时间 / 年份 / 国家数，供 {@code GET /api/world/save-info} 使用。
     * 存档不存在时 exists=false、其余字段为 -1 / 0；存档读不出来（坏档或正好写在中间）
     * 时用运行时状态兜底，保证接口始终可用。
     */
    public SaveInfo saveInfo() {
        File file = new File(SAVE_PATH);
        if (!file.isFile()) {
            return new SaveInfo(false, null, 0L, -1, -1, 0L);
        }
        long stamp = file.lastModified();
        try {
            WorldSave save = objectMapper.readValue(file, WorldSave.class);
            int civCount = save.civilizations() == null ? 0 : save.civilizations().size();
            return new SaveInfo(true, formatSaveTime(stamp), stamp, save.year(), civCount, file.length());
        } catch (Exception e) {
            log.warn("读取存档概要失败，退回运行时状态：{}", e.getMessage());
            return new SaveInfo(true, formatSaveTime(stamp), stamp, world.getYear(),
                    world.getCivilizations().size(), file.length());
        }
    }

    private static String formatSaveTime(long epochMillis) {
        return SAVE_TIME_FORMAT.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * 存档概要。
     *
     * @param exists            存档文件是否存在
     * @param savedAt           存档时间（本地时间文本，yyyy-MM-dd HH:mm:ss）
     * @param savedAtEpochMillis 存档时间（毫秒时间戳）
     * @param year              存档里的年份（不存在时 -1）
     * @param civilizationCount 存档里的文明数（不存在时 -1）
     * @param fileSize          存档文件字节数
     */
    public record SaveInfo(boolean exists, String savedAt, long savedAtEpochMillis, int year,
            int civilizationCount, long fileSize) {
    }

    /** 服务关闭时把当前世界状态落盘，避免丢失最后一次百年存档之后的进度。 */
    @PreDestroy
    public void saveOnShutdown() {
        log.info("服务关闭，正在保存世界状态……");
        save();
    }

    /** 收集当前世界状态。 */
    private WorldSave snapshot() {
        Planet planet = world.getPlanet();
        List<CivilizationSave> civilizations = new ArrayList<>();
        for (Civilization civilization : world.getCivilizations()) {
            civilizations.add(new CivilizationSave(civilization.getId(), civilization.getName(),
                    civilization.getFoundedYear(), civilization.getPopulation(), civilization.getTechLevel(),
                    new ArrayList<>(civilization.getTerritory()), civilization.getScience(),
                    civilization.getWelfare(), civilization.getResource(), civilization.getMilitary(),
                    new ArrayList<>(civilization.getUnlockedTechs()),
                    civilization.getPersonality(), new LinkedHashMap<>(civilization.getRelations()),
                    new ArrayList<>(civilization.getMemories()),
                    civilization.getShortTermGoal(), civilization.getLongTermGoal(),
                    civilization.getGoalProgress(),
                    // 五种资源点 + 五个产出系数 + 连续和平年数 + 四个状态指标
                    new LinkedHashMap<>(civilization.pointMap()),
                    multipliersOf(civilization),
                    civilization.getPeaceYears(),
                    civilization.getUnrest(), civilization.getCulture(),
                    civilization.getPrestige(), civilization.getTension()));
        }
        List<GameEvent> events = new ArrayList<>();
        for (GameEvent event : worldMapper.selectEvents()) {
            if (event != null) {
                events.add(event);
            }
        }
        // 地形：静态区域由 RegionMap 定义，动态区域（如「飞出地球」新地盘）可能改过，一并存下
        Map<Integer, String> regionTerrains = new LinkedHashMap<>();
        for (Region region : world.getRegionMap().all()) {
            regionTerrains.put(region.getId(), region.getTerrain());
        }
        return new WorldSave(world.getYear(), planet.getName(), planet.getTemperature(),
                planet.getHabitability(), engine.getFood(), engine.getResources(),
                engine.currentEraIndex(), civilizations, events, world.exploredRegionIds(),
                engine.getResearchMode(), engine.getSeeds(), engine.isGameOver(), engine.getGameOverReason(),
                regionTerrains, engine.getChaosLevel());
    }

    /** 把存档写回世界（顺序：星球 -> 文明 -> 年份 -> 探索 -> 事件 -> 引擎状态）。 */
    private void restore(WorldSave save) {
        Planet planet = world.getPlanet();
        if (save.planetName() != null) {
            planet.setName(save.planetName());
        }
        planet.setTemperature(save.temperature());
        planet.setHabitability(save.habitability());

        world.clearCivilizations();
        if (save.civilizations() != null) {
            for (CivilizationSave c : save.civilizations()) {
                if (c == null) {
                    continue;
                }
                Civilization civilization = new Civilization(c.id(), c.name(), c.foundedYear(), c.population());
                civilization.setTechLevel(c.techLevel());
                // 四柱：旧存档没有这几个字段（读出 null）时回落到初始值 50，
                // 不能用原始 int——缺字段会被 Jackson 读成 0，那样一进游戏就是「四柱全崩」
                civilization.setScience(pillarOr(c.science()));
                civilization.setWelfare(pillarOr(c.welfare()));
                civilization.setResource(pillarOr(c.resource()));
                civilization.setMilitary(pillarOr(c.military()));
                civilization.replaceTerritory(c.territory());
                // 科技树：旧存档没有这几个字段，unlockedTechs / currentResearchId 读出 null、进度读出 0
                civilization.replaceUnlockedTechs(c.unlockedTechs());
                // 五种资源点：旧存档没有这个字段（读出 null）时按全 0 处理
                restorePoints(civilization, c.points());
                // 五种产出系数：旧存档没有时保持默认 1.0
                restoreMultipliers(civilization, c.multipliers());
                // 连续和平年数：旧存档没有时按 0 处理
                civilization.setPeaceYears(c.peaceYears() == null ? 0 : c.peaceYears());
                // 四个状态指标：旧存档没有时按 0 处理
                civilization.replaceIndicators(c.unrest(), c.culture(), c.prestige(), c.tension());
                // 性格：旧存档没有这个字段（读出 null）时保留构造时随机抽的那一个
                if (c.personality() != null && !c.personality().isBlank()) {
                    civilization.setPersonality(c.personality());
                }
                // 关系值：旧存档没有这个字段（读出 null）时按空表处理
                civilization.replaceRelations(c.relations());
                // 记忆：旧存档没有这个字段（读出 null）时按空表处理
                civilization.replaceMemories(c.memories());
                // 目标：旧存档没有这几个字段（读出 null）时按「还没有目标」处理
                civilization.replaceGoals(c.shortTermGoal(), c.longTermGoal(), c.goalProgress());
                world.restoreCivilization(civilization);
            }
        }

        world.setYear(save.year());

        // 探索状态：civilizations 已放回，旧存档缺这一项时按玩家领土重算一圈（exploredRegionIds 为 null）
        world.restoreExploration(save.exploredRegionIds(), PLAYER_CIV_ID);

        // 地形：旧存档没有这个字段（读出 null）时保留 RegionMap 构造时的默认分配
        if (save.regionTerrains() != null) {
            for (Map.Entry<Integer, String> entry : save.regionTerrains().entrySet()) {
                Region region = world.getRegionMap().get(entry.getKey());
                if (region != null && entry.getValue() != null) {
                    region.setTerrain(entry.getValue());
                }
            }
        }

        List<GameEvent> events = new ArrayList<>();
        if (save.events() != null) {
            for (GameEvent event : save.events()) {
                if (event != null) {
                    events.add(event);
                }
            }
        }
        worldMapper.replaceEvents(events);

        engine.restoreState(save.food(), save.resources(), save.currentEraIndex());
        // 研究模式：旧存档没有这个字段（读出 null）时保持引擎默认的 passive
        if (save.researchMode() != null) {
            engine.setResearchMode(save.researchMode());
        }
        // 因果种子：旧存档没有这个字段（读出 null）时按空列表处理
        engine.replaceSeeds(save.seeds());
        // 终局状态：旧存档没有这两个字段时按「未结束」处理
        engine.restoreGameOver(save.gameOver(), save.gameOverReason());
        // 混乱度：旧存档没有这个字段（读出 null）时按 0 处理
        engine.setChaosLevel(save.chaosLevel() == null ? 0 : save.chaosLevel());
    }

    /**
     * 存档文件结构。exploredRegionIds 是玩家已探索（非黑雾）的区域 id；regionTerrains 是区域地形（旧存档缺省）；
     * chaosLevel 是世界混乱度（旧存档缺省，读出 null 时按 0 处理）。
     */
    public record WorldSave(int year, String planetName, double temperature, double habitability,
            double food, double resources, int currentEraIndex,
            List<CivilizationSave> civilizations, List<GameEvent> events, List<Integer> exploredRegionIds,
            String researchMode, List<Seed> seeds, boolean gameOver, String gameOverReason,
            Map<Integer, String> regionTerrains, Integer chaosLevel) {
    }

    /**
     * 存档里的单个文明。四柱 / 状态指标用包装类型：旧存档缺这几个字段时读出 null，
     * 好和「真的是 0」区分开。
     *
     * @param points      五种资源点（键 food / production / military / culture / science）；旧存档为 null
     * @param multipliers 五种产出系数；旧存档为 null（恢复到默认 1.0）
     * @param peaceYears  连续和平年数；旧存档为 null
     * @param unrest      民怨 0~100；旧存档为 null
     * @param culture     文化水平 0~1000（与资源点里的 culture 是两回事）；旧存档为 null
     * @param prestige    威望 0~1000；旧存档为 null
     * @param tension     紧张度 0~100；旧存档为 null
     */
    public record CivilizationSave(long id, String name, int foundedYear, long population, int techLevel,
            List<Integer> territory, Integer science, Integer welfare, Integer resource, Integer military,
            List<String> unlockedTechs, String personality, Map<String, Integer> relations,
            List<Memory> memories, String shortTermGoal, String longTermGoal, Integer goalProgress,
            Map<String, Integer> points, Map<String, Double> multipliers, Integer peaceYears,
            Integer unrest, Integer culture, Integer prestige, Integer tension) {
    }

    /** 存档里缺四柱字段（旧存档）时按初始值 50 处理。 */
    private static int pillarOr(Integer value) {
        return value == null ? Civilization.PILLAR_INITIAL : value;
    }

    /** 五种产出系数的快照。 */
    private static Map<String, Double> multipliersOf(Civilization civilization) {
        Map<String, Double> multipliers = new LinkedHashMap<>();
        for (String type : Civilization.POINT_TYPES) {
            multipliers.put(type, civilization.getMultiplier(type));
        }
        return multipliers;
    }

    /** 写回五种资源点；旧存档没这个字段（null）时保持全 0。 */
    private static void restorePoints(Civilization civilization, Map<String, Integer> points) {
        if (points == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : points.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                civilization.setPoints(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 写回五种产出系数；旧存档没这个字段（null）时保持默认 1.0。 */
    private static void restoreMultipliers(Civilization civilization, Map<String, Double> multipliers) {
        if (multipliers == null) {
            return;
        }
        for (Map.Entry<String, Double> entry : multipliers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                civilization.setMultiplier(entry.getKey(), entry.getValue());
            }
        }
    }
}
