import com.fasterxml.jackson.databind.ObjectMapper;
import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.api.ChatController;
import com.planet.evolution.mapper.impl.WorldMapperImpl;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Region;
import com.planet.evolution.model.World;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.GameEvent;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** 用反射直接调用私有 doAttack，确定性地覆盖四种胜负分支与各拒绝路径。 */
public class ChatAttackProbe {

    private static final StringBuilder LOG = new StringBuilder();

    public static void main(String[] args) throws Exception {
        testNullTarget();
        testBranches();
        Files.write(Path.of(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    /** 目标为空：不应去查 RegionMap。 */
    private static void testNullTarget() throws Exception {
        Fixture f = new Fixture();
        log("=== 目标为空 ===");
        log("  target=null  -> " + attack(f, null));
        log("  target=\"\"    -> " + attack(f, "   "));
    }

    /** 四种实力对比。 */
    private static void testBranches() throws Exception {
        log("");
        log("=== 实力分支（玩家/守方 人口比）===");
        // 占优：玩家 100000 / 守方 10000 = 10 倍
        runCase("占优 ratio=10.0", 100_000L, 10_000L);
        // 势均力敌：1.0
        runCase("势均力敌 ratio=1.0", 50_000L, 50_000L);
        // 劣势：0.5
        runCase("劣势 ratio=0.5", 25_000L, 50_000L);
        // 惨败:0.1（玩家 6000，损失 60% = 3600，高于灭绝线 200，不会死）
        runCase("惨败 ratio=0.1", 6_000L, 60_000L);
        // 惨败且玩家灭亡：玩家 300，损失 60% = 180 < 200 灭绝线
        runCase("惨败且玩家灭亡", 300L, 60_000L);
        // 守方被打到灭绝线以下（300 × 0.6 = 180 < 200）→ 应灭国并把区域划给玩家
        runCase("占优且守方灭亡吞并", 100_000L, 300L);
    }

    private static void runCase(String label, long playerPop, long defenderPop) throws Exception {
        Fixture f = new Fixture();
        f.player.setPopulation(playerPop);
        f.defender.setPopulation(defenderPop);
        String target = f.target.getName();
        String message = attack(f, target);
        log("");
        log("--- " + label + " ---");
        log("  玩家 人口 " + playerPop + " -> " + f.player.getPopulation()
                + "（领土 " + f.player.getTerritory().size() + " 块）");
        log("  守方 人口 " + defenderPop + " -> " + f.defender.getPopulation());
        log("  目标区域归属: " + f.target.getOwnerCivId() + "（玩家 id=" + f.player.getId()
                + "，守方 id=" + f.defender.getId() + "）");
        log("  返回: " + message);
        log("  新事件: " + describeLastEvents(f));
    }

    private static String describeLastEvents(Fixture f) {
        StringBuilder sb = new StringBuilder();
        for (GameEvent e : f.mapper.selectEvents()) {
            sb.append('[').append(e.type()).append(" r").append(e.rarity()).append("] ")
              .append(e.description()).append(" | ");
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    private static String attack(Fixture f, String target) throws Exception {
        Method method = ChatController.class.getDeclaredMethod("doAttack", Civilization.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(f.controller, f.player, target);
    }

    /** 每次测试用的独立世界：玩家 id=1，另建一个守方文明并让它占住玩家领土的邻接区域。 */
    private static class Fixture {
        final World world;
        final WorldMapperImpl mapper = new WorldMapperImpl();
        final Civilization player;
        final Civilization defender;
        final Region target;
        final ChatController controller;

        Fixture() {
            world = new World(new Planet("盖亚", 15.0, 1.0), new Random(20261009L));
            player = world.spawnCivilization("玩家文明");
            // 挑一块与玩家领土接壤的区域给守方
            Region chosen = null;
            for (Integer ownedId : player.getTerritory()) {
                Region owned = world.getRegionMap().get(ownedId);
                for (Integer neighborId : owned.getNeighbors()) {
                    Region neighbor = world.getRegionMap().get(neighborId);
                    if (neighbor != null && neighbor.isUnowned()) {
                        chosen = neighbor;
                        break;
                    }
                }
                if (chosen != null) {
                    break;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("找不到接壤的无主区域用于测试");
            }
            target = chosen;
            defender = world.spawnCivilization("守方文明");
            // 让守方占住目标区域
            defender.replaceTerritory(java.util.List.of(target.getId()));
            target.setOwnerCivId((int) defender.getId());

            EvolutionEngine engine = new EvolutionEngine(world, new EarthLikeProfile(), 20261009L);
            controller = new ChatController(world, engine, new AiNarrator(), mapper, new ObjectMapper());
        }
    }

    private static void log(String line) {
        LOG.append(line).append('\n');
    }
}
