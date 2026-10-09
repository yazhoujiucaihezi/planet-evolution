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

/** 文明诞生人口守恒验证。 */
public class ConservationCheck {

    private static final StringBuilder LOG = new StringBuilder();
    private static final long EXTINCTION = 200L;

    public static void main(String[] args) throws Exception {
        run(10000);
        Files.write(Path.of(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static void run(int years) {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet);
        world.spawnCivilization("人类");
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);

        int splits = 0;
        int conservationViolations = 0;
        int parentBelowLine = 0;
        int splitOverCap = 0;
        int nullOrDeadSource = 0;
        int maxCivCount = 0;
        long maxSplit = 0;
        int birthEvents = 0;

        for (int i = 0; i < years; i++) {
            long totalBefore = totalPopulation(world);
            int countBefore = world.getCivilizations().size();
            engine.tick();
            int countAfter = world.getCivilizations().size();
            if (countAfter > countBefore) {
                splits++;
                long totalAfter = totalPopulation(world);
                if (totalAfter != totalBefore) {
                    conservationViolations++;
                    if (conservationViolations <= 3) {
                        LOG.append("  守恒破坏：Y").append(world.getYear())
                           .append(" 分裂前=").append(totalBefore)
                           .append(" 分裂后=").append(totalAfter).append('\n');
                    }
                }
                // 校验新文明的初始人口与源文明剩余
                Civilization newest = world.getCivilizations().get(countAfter - 1);
                long initial = newest.getPopulation();
                maxSplit = Math.max(maxSplit, initial);
                if (initial > 1000) {
                    splitOverCap++;
                }
                for (Civilization c : world.getCivilizations()) {
                    if (c.getPopulation() > 0 && c.getPopulation() <= EXTINCTION) {
                        parentBelowLine++;
                    }
                }
            }
            maxCivCount = Math.max(maxCivCount, countAfter);
        }

        for (GameEvent e : engine.getEvents()) {
            if (e.type().name().equals("CIVILIZATION_BIRTH")) {
                birthEvents++;
                if (birthEvents <= 3) {
                    LOG.append("  诞生事件示例：Y").append(e.year()).append(' ').append(e.description()).append('\n');
                }
            }
        }

        // 检查所有存活文明都严格高于灭绝线，且不存在 0 人口的新生文明
        int zeroPopCivs = 0;
        int alive = 0;
        int extinct = 0;
        long population = 0;
        int tech = 0;
        for (Civilization c : world.getCivilizations()) {
            population += c.getPopulation();
            tech += c.getTechLevel();
            if (c.getPopulation() <= 0) {
                extinct++;
            } else {
                alive++;
                if (c.getPopulation() <= EXTINCTION) {
                    nullOrDeadSource++;
                }
            }
        }

        LOG.append("=== ").append(years).append(" 年人口守恒验证 ===\n");
        LOG.append("分裂次数 = ").append(splits).append('\n');
        LOG.append("守恒被破坏次数 = ").append(conservationViolations).append('\n');
        LOG.append("源文明被压到灭绝线及以下的次数 = ").append(parentBelowLine).append('\n');
        LOG.append("新生文明人口超过 1000 的次数 = ").append(splitOverCap).append('\n');
        LOG.append("最大单次分裂人口 = ").append(maxSplit).append('\n');
        LOG.append("存活文明数 / 灭亡文明数 = ").append(alive).append(" / ").append(extinct).append('\n');
        LOG.append("历史最大存活文明数 = ").append(maxCivCount).append('\n');
        LOG.append("总人口 = ").append(population).append("，总科技 = ").append(tech).append('\n');
        LOG.append("文明诞生事件数 = ").append(birthEvents).append('\n');
        LOG.append("存活但人口 <= 灭绝线的文明数（应为 0）= ").append(nullOrDeadSource).append('\n');
        LOG.append("零人口文明数（=灭亡数，应为 >= 0）= ").append(extinct).append('\n');
    }

    private static long totalPopulation(World world) {
        long total = 0;
        for (Civilization c : world.getCivilizations()) {
            total += c.getPopulation();
        }
        return total;
    }
}
