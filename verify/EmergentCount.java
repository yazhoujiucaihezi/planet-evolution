import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.PlanetProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 涌现事件长跑统计：跑 1000 年，统计四个涌现事件各触发几次。
 * 四个事件都以 AI_EVENT 记录，离线（无 AI）时用兜底文案，按关键词区分。
 */
public class EmergentCount {

    public static void main(String[] args) {
        int years = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261009L;

        Planet planet = new Planet("盖亚", 15.0, 1.0);
        // 区域分配也用固定种子，保证同一 seed 下可复现
        World world = new World(planet, new java.util.Random(seed));
        world.spawnCivilization("人类");
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, seed);
        // 离线跑：关掉 AI 叙事，涌出事件走兜底文案（确定性）
        engine.setNarrateEnabled(false);

        for (int i = 0; i < years; i++) {
            engine.tick();
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("内战", 0);
        counts.put("文化繁荣", 0);
        counts.put("伟人诞生", 0);
        counts.put("外交危机", 0);
        int totalEvents = 0;
        for (GameEvent e : engine.getEvents()) {
            totalEvents++;
            String d = e.description();
            if (d == null) {
                continue;
            }
            if (d.contains("揭竿而起")) {
                counts.merge("内战", 1, Integer::sum);
            } else if (d.contains("黄金时代")) {
                counts.merge("文化繁荣", 1, Integer::sum);
            } else if (d.contains("天才学者")) {
                counts.merge("伟人诞生", 1, Integer::sum);
            } else if (d.contains("外交危机")) {
                counts.merge("外交危机", 1, Integer::sum);
            }
        }

        System.out.println("=== 涌现事件统计（" + years + " 年，seed=" + seed + "）===");
        counts.forEach((k, v) -> System.out.println("  " + k + " = " + v + " 次"));
        System.out.println("  事件总数 = " + totalEvents);
        System.out.println("  年份 = " + world.getYear() + "，存活文明 = " + world.aliveCivilizationCount()
                + "，灭亡文明 = " + world.extinctCivilizationCount());
        int tech = 0;
        long pop = 0;
        for (Civilization c : world.getCivilizations()) {
            tech += c.getTechLevel();
            pop += c.getPopulation();
        }
        System.out.println("  总人口 = " + pop + "，总科技 = " + tech);
    }
}
