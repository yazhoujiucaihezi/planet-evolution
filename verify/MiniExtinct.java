import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.PlanetProfile;

/** 逐步观测：灭绝那年前后的人口变化。 */
public class MiniExtinct {
    public static void main(String[] args) {
        Planet planet = new Planet("荒漠", 60.0, 0.0);
        World world = new World(planet);
        Civilization c = world.spawnCivilization("人类");
        EvolutionEngine engine = new EvolutionEngine(world, new Barren(), 1L);
        for (int i = 0; i < 40; i++) {
            engine.tick();
            if (i >= 28) {
                System.out.println("Y" + world.getYear()
                        + " 人口=" + c.getPopulation()
                        + " 食物=" + engine.getFood()
                        + " 资源=" + engine.getResources()
                        + " 事件=" + engine.getEvents().size());
            }
        }
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
