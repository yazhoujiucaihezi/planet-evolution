package com.planet.evolution.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planet.evolution.dto.RegionView;
import com.planet.evolution.dto.WorldStateResponse;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Region;
import com.planet.evolution.model.World;
import com.planet.evolution.service.ClockService;
import com.planet.evolution.service.SaveService;
import com.planet.evolution.service.WorldService;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;

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

    private final WorldService worldService;
    private final ClockService clockService;
    private final World world;
    private final EvolutionEngine engine;
    private final SaveService saveService;

    public WorldController(WorldService worldService, ClockService clockService, World world,
            EvolutionEngine engine, SaveService saveService) {
        this.worldService = worldService;
        this.clockService = clockService;
        this.world = world;
        this.engine = engine;
        this.saveService = saveService;
    }

    @GetMapping
    public WorldStateResponse getWorld() {
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
        return new WorldStateResponse(state, clockService.currentSpeed(), clockService.isPaused(), civilizations);
    }

    @GetMapping("/events")
    public List<GameEvent> getEvents() {
        return worldService.listEvents();
    }

    /** 所有区域及其归属。ownerCivId 为 0 表示无主，ownerCivName 为 null。 */
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
            views.add(new RegionView(region.getId(), region.getName(), ownerId, ownerName));
        }
        return views;
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
}
