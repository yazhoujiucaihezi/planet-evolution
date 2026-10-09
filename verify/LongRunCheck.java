import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.EventType;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.PlanetProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 长期演化验证：200 年与 1000 年，输出统计并检查数值合法性。 */
public class LongRunCheck {

    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        run(200);
        log("");
        run(1000);
        Files.write(Path.of(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
        log("written");
    }

    private static void log(String line) {
        LOG.append(line).append('\n');
    }

    private static void run(int years) {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet);
        world.spawnCivilization("人类");
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);

        boolean finite = true;
        boolean nonNegative = true;
        double minFood = Double.MAX_VALUE;

        for (int i = 0; i < years; i++) {
            engine.tick();
            finite &= isFinite(engine.getFood()) && isFinite(engine.getResources()) && isFinite(planet.getHabitability());
            nonNegative &= engine.getFood() >= 0 && engine.getResources() >= 0;
            minFood = Math.min(minFood, engine.getFood());
            for (Civilization c : world.getCivilizations()) {
                finite &= c.getPopulation() >= 0 && c.getTechLevel() >= 0;
                nonNegative &= c.getPopulation() >= 0 && c.getTechLevel() >= 0;
            }
        }

        log("=== " + years + " 年模拟 ===");
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> rarityBand = new LinkedHashMap<>();
        int major = 0;
        for (GameEvent e : engine.getEvents()) {
            byType.merge(e.type().getLabel(), 1, Integer::sum);
            String band = e.rarity() >= 60 ? "重大(>=60)" : "普通(<60)";
            rarityBand.merge(e.type().getLabel() + " " + band, 1, Integer::sum);
            if (e.rarity() >= 60) {
                major++;
            }
        }
        log("事件总数=" + engine.getEvents().size() + "，重大事件=" + major);
        byType.forEach((k, v) -> log("  " + k + " = " + v));
        log("稀有度分档：");
        rarityBand.forEach((k, v) -> log("  " + k + " = " + v));

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
        log("年份=" + world.getYear() + " 总人口=" + population + " 总科技=" + tech);
        log("食物=" + engine.getFood() + " 资源=" + engine.getResources());
        log("存活文明=" + alive + " 灭亡文明=" + dead);
        log("数值有限=" + finite + " 数值非负=" + nonNegative + " 食物最低值=" + minFood);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
