import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.PlanetProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 文明灭亡机制验证：断粮灭绝、灭亡后不参与演化、其余文明继续演化。 */
public class ExtinctionCheck {

    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        starvationScenario();
        log("");
        partialExtinctionScenario();
        Files.write(Path.of(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    /** 场景一：宜居度为 0，产出为零，全文明持续断粮走向灭亡。 */
    private static void starvationScenario() {
        log("=== 场景一：持续断粮（宜居度 0，产出为 0）===");
        Planet planet = new Planet("荒漠", 60.0, 0.0);
        World world = new World(planet);
        Civilization human = world.spawnCivilization("人类");
        PlanetProfile barren = new BarrenProfile();
        EvolutionEngine engine = new EvolutionEngine(world, barren, 20261009L);

        long previous = human.getPopulation();
        int declineYears = 0;
        int increaseYears = 0;
        for (int i = 1; i <= 60; i++) {
            engine.tick();
            long now = human.getPopulation();
            if (now < previous) {
                declineYears++;
            } else if (now > previous) {
                increaseYears++;
            }
            previous = now;
            if (i % 10 == 0) {
                log("  Y" + world.getYear() + " 人口=" + now + " 食物=" + engine.getFood()
                        + " 资源=" + engine.getResources() + " 科技=" + human.getTechLevel());
            }
        }
        log("  人口下降年数=" + declineYears + " 上升年数=" + increaseYears);
        log("  最终人口=" + human.getPopulation());

        boolean extinctEvent = false;
        int extinctionCount = 0;
        for (GameEvent e : engine.getEvents()) {
            if (e.type().name().equals("EXTINCTION")) {
                extinctEvent = true;
                extinctionCount++;
                log("  EXTINCTION 事件: Y" + e.year() + " 稀有度=" + e.rarity() + " " + e.description());
            }
        }
        log("  触发 EXTINCTION = " + extinctEvent + "，事件条数 = " + extinctionCount);

        long before = human.getPopulation();
        int techBefore = human.getTechLevel();
        engine.tick();
        engine.tick();
        log("  灭亡后再推进 2 年：人口 " + before + " -> " + human.getPopulation()
                + "，科技 " + techBefore + " -> " + human.getTechLevel()
                + "，资源=" + engine.getResources() + " 食物=" + engine.getFood());
        log("  灭亡文明人口保持为 0 = " + (human.getPopulation() == 0));
        log("  灭亡文明科技不再增长 = " + (human.getTechLevel() == techBefore));
    }

    /** 场景二：一个濒危文明灭亡，另一个文明继续演化。 */
    private static void partialExtinctionScenario() {
        log("=== 场景二：一个文明灭亡，其余文明继续演化（种子固定）===");
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet);
        Civilization human = world.spawnCivilization("人类");
        Civilization tribe = world.spawnCivilization("濒危部落");
        tribe.setPopulation(150L);
        EvolutionEngine engine = new EvolutionEngine(world, new EarthLikeProfile(), 20261009L);

        long humanStart = human.getPopulation();
        int extinctAt = -1;
        for (int i = 1; i <= 200; i++) {
            engine.tick();
            if (extinctAt < 0 && tribe.getPopulation() == 0) {
                extinctAt = world.getYear();
            }
        }
        log("  濒危部落(初始 150) 灭亡年份 = " + extinctAt + "，人口 = " + tribe.getPopulation());
        log("  人类 人口 " + humanStart + " -> " + human.getPopulation() + "，科技 = " + human.getTechLevel());
        log("  人类仍在演化 = " + (human.getPopulation() > humanStart && human.getTechLevel() > 1));

        boolean extinctionEvent = false;
        int extinctionCount = 0;
        for (GameEvent e : engine.getEvents()) {
            if (e.type().name().equals("EXTINCTION")) {
                extinctionEvent = true;
                extinctionCount++;
                log("  EXTINCTION 事件: Y" + e.year() + " " + e.description() + " 稀有度=" + e.rarity());
            }
        }
        log("  触发 EXTINCTION = " + extinctionEvent + "，事件条数 = " + extinctionCount);

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (GameEvent e : engine.getEvents()) {
            byType.merge(e.type().getLabel(), 1, Integer::sum);
        }
        byType.forEach((k, v) -> log("  事件 " + k + " = " + v));
    }

    /** 无产出星球参数，用于制造持续断粮。 */
    private static class BarrenProfile implements PlanetProfile {
        @Override
        public String getName() {
            return "荒漠星球";
        }

        @Override
        public double foodPerCapita() {
            return 0;
        }

        @Override
        public double resourceYield() {
            return 0;
        }

        @Override
        public double resourceUpkeep() {
            return 0.45;
        }

        @Override
        public double techGrowth() {
            return 0.05;
        }

        @Override
        public double disasterChance() {
            return 0;
        }

        @Override
        public int warTechThreshold() {
            return 12;
        }

        @Override
        public long extinctionPopulation() {
            return 200L;
        }
    }

    private static void log(String line) {
        LOG.append(line).append('\n');
    }
}
