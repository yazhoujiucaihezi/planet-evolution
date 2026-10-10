package com.planet.evolution.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
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
            // 存档损坏也不能阻止启动
            log.error("读取存档失败，继续使用当前世界状态：{}", e.getMessage(), e);
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
        try {
            WorldSave save = snapshot();
            File file = new File(SAVE_PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log.warn("无法创建存档目录 {}", parent.getAbsolutePath());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, save);
            log.info("世界已存档到 {}（年份 {}，文明 {} 个，事件 {} 条）", SAVE_PATH, save.year(),
                    save.civilizations().size(), save.events().size());
            return null;
        } catch (Exception e) {
            log.error("写入存档失败，已忽略：{}", e.getMessage(), e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
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
                    new ArrayList<>(civilization.getTerritory())));
        }
        List<GameEvent> events = new ArrayList<>();
        for (GameEvent event : worldMapper.selectEvents()) {
            if (event != null) {
                events.add(event);
            }
        }
        return new WorldSave(world.getYear(), planet.getName(), planet.getTemperature(),
                planet.getHabitability(), engine.getFood(), engine.getResources(),
                engine.currentEraIndex(), civilizations, events);
    }

    /** 把存档写回世界（顺序：星球 -> 文明 -> 年份 -> 事件 -> 引擎状态）。 */
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
                civilization.replaceTerritory(c.territory());
                world.restoreCivilization(civilization);
            }
        }

        world.setYear(save.year());

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
    }

    /** 存档文件结构。 */
    public record WorldSave(int year, String planetName, double temperature, double habitability,
            double food, double resources, int currentEraIndex,
            List<CivilizationSave> civilizations, List<GameEvent> events) {
    }

    /** 存档里的单个文明。 */
    public record CivilizationSave(long id, String name, int foundedYear, long population, int techLevel,
            List<Integer> territory) {
    }
}
