import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.PlanetProfile;

/** 最直接的复现：单文明 + 零产出，逐步打印每个文明的人口。 */
public class Probe {
    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        Planet planet = new Planet("荒漠", 60.0, 0.0);
        World world = new World(planet);
        world.spawnCivilization("人类");
        EvolutionEngine engine = new EvolutionEngine(world, new Barren(), 1L);

        for (int i = 0; i < 40; i++) {
            engine.tick();
            StringBuilder pops = new StringBuilder();
            for (Civilization c : world.getCivilizations()) {
                pops.append(c.getName()).append('=').append(c.getPopulation()).append(' ');
            }
            sb.append("Y").append(world.getYear())
              .append(" 文明数=").append(world.getCivilizations().size())
              .append(" 人口[").append(pops.toString().trim()).append(']')
              .append(" 事件数=").append(engine.getEvents().size()).append('\n');
        }
        java.nio.file.Files.write(java.nio.file.Path.of(args[0]),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("written");
    }

    static class Barren implements PlanetProfile {
        public String getName() {
            return "荒漠";
        }

        public double foodPerCapita() {
            return 0;
        }

        public double resourceYield() {
            return 0;
        }

        public double resourceUpkeep() {
            return 0.45;
        }

        public double techGrowth() {
            return 0.05;
        }

        public double disasterChance() {
            return 0;
        }

        public int warTechThreshold() {
            return 12;
        }

        public long extinctionPopulation() {
            return 200L;
        }
    }
}
