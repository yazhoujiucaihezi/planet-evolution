package com.planet.evolution.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planet.evolution.dto.WorldStateResponse;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.service.ClockService;
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

    private final WorldService worldService;
    private final ClockService clockService;
    private final World world;
    private final EvolutionEngine engine;

    public WorldController(WorldService worldService, ClockService clockService, World world,
            EvolutionEngine engine) {
        this.worldService = worldService;
        this.clockService = clockService;
        this.world = world;
        this.engine = engine;
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

    private ClockResult clockResult() {
        return new ClockResult(clockService.isPaused(), clockService.currentSpeed());
    }

    public record AdvanceRequest(int years) {
    }

    public record AdvanceResult(int year, int advancedYears) {
    }

    public record SpeedRequest(int speed) {
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
