import com.planet.evolution.model.World;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.simulation.GameEvent;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.PlanetProfile;
import com.planet.evolution.simulation.EarthLikeProfile;

public class EngineDemo {
    public static void main(String[] args) {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet);
        world.spawnCivilization("人类");
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);

        for (int i = 0; i < 200; i++) {
            engine.tick();
            if (world.getYear() % 25 == 0) {
                StringBuilder sb = new StringBuilder();
                for (Civilization c : world.getCivilizations()) {
                    sb.append(c.getName()).append(":人口=").append(c.getPopulation())
                      .append(",科技=").append(c.getTechLevel()).append(" ");
                }
                System.out.println(String.format("Y%-4d 食物=%-12.0f 资源=%-12.0f %s",
                        world.getYear(), engine.getFood(), engine.getResources(), sb.toString().trim()));
            }
        }

        System.out.println("---- 事件总数: " + engine.getEvents().size() + " ----");
        int shown = 0;
        for (GameEvent e : engine.getEvents()) {
            System.out.println("Y" + e.year() + " [" + e.type().getLabel() + "] " + e.description());
            if (++shown >= 20) {
                break;
            }
        }
    }
}
