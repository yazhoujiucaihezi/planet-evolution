import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.MajorEventRules;
import com.planet.evolution.simulation.PlanetProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 1000 年长期趋势：每 100 年记录一次，并检查数值合法性与战争稀有度。 */
public class LongRunTrend {

    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        log("阈值 MAJOR_THRESHOLD = " + MajorEventRules.MAJOR_THRESHOLD);
        log("");
        log("年份\t总人口\t总科技\t食物\t资源\t存活\t灭亡");
        run(1000, args[0]);
        Files.write(Path.of(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static void run(int years, String ignored) {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet);
        world.spawnCivilization("人类");
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);

        boolean finite = true;
        boolean nonNegative = true;
        long maxPopulation = 0;
        double maxFood = 0;
        double maxResources = 0;
        boolean anyResurrect = false;
        Map<Long, Long> lastPopulation = new LinkedHashMap<>();
        Map<String, Integer> warByMajor = new LinkedHashMap<>();
        int warRarityMin = Integer.MAX_VALUE;
        int warRarityMax = Integer.MIN_VALUE;

        for (int i = 1; i <= years; i++) {
            engine.tick();

            for (Civilization c : world.getCivilizations()) {
                finite &= Double.isFinite(c.getPopulation()) && Double.isFinite(c.getTechLevel());
                nonNegative &= c.getPopulation() >= 0 && c.getTechLevel() >= 0;
            }
            finite &= Double.isFinite(engine.getFood()) && Double.isFinite(engine.getResources());
            nonNegative &= engine.getFood() >= 0 && engine.getResources() >= 0;

            // 灭亡文明复活检查：按文明 id 比较上一次的人口
            for (Civilization c : world.getCivilizations()) {
                Long before = lastPopulation.get(c.getId());
                if (before != null && before == 0 && c.getPopulation() > 0) {
                    anyResurrect = true;
                }
                lastPopulation.put(c.getId(), c.getPopulation());
            }

            if (world.getYear() % 100 == 0) {
                long population = 0;
                int tech = 0;
                int alive = 0;
                int dead = 0;
                for (Civilization c : world.getCivilizations()) {
                    population += c.getPopulation();
                    tech += c.getTechLevel();
                    if (c.getPopulation() > 0) {
                        alive++;
                    } else {
                        dead++;
                    }
                }
                maxPopulation = Math.max(maxPopulation, population);
                maxFood = Math.max(maxFood, engine.getFood());
                maxResources = Math.max(maxResources, engine.getResources());
                log(world.getYear() + "\t" + population + "\t" + tech + "\t"
                        + Math.round(engine.getFood()) + "\t" + Math.round(engine.getResources())
                        + "\t" + alive + "\t" + dead);
            }
        }

        for (GameEvent e : engine.getEvents()) {
            if (e.type().name().equals("WAR")) {
                warRarityMin = Math.min(warRarityMin, e.rarity());
                warRarityMax = Math.max(warRarityMax, e.rarity());
                warByMajor.merge(e.rarity() >= MajorEventRules.MAJOR_THRESHOLD ? "重大战争" : "普通战争",
                        1, Integer::sum);
            }
        }

        log("");
        log("数值有限 = " + finite + "，数值非负 = " + nonNegative);
        log("灭亡文明复活 = " + anyResurrect);
        log("最高总人口 = " + maxPopulation + "，最高食物 = " + Math.round(maxFood)
                + "，最高资源 = " + Math.round(maxResources));
        log("战争稀有度范围 = " + (warRarityMin == Integer.MAX_VALUE ? "无战争" :
                warRarityMin + "~" + warRarityMax));
        warByMajor.forEach((k, v) -> log("  " + k + " = " + v));
        log("食物是否长期卡在 1000000 = " + (maxFood >= 1_000_000));
    }

    private static void log(String line) {
        LOG.append(line).append('\n');
    }
}
