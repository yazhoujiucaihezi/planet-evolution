import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Player;
import com.planet.evolution.model.World;
import com.planet.evolution.service.PlayerService;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.PlanetProfile;

import java.lang.reflect.Field;
import java.util.List;

/** 联机两个 bug 的针对性验证：文明分配跳过规则 + 终局判定按「玩家当前控制的文明」。 */
public class MultiplayerCheck {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        checkAllocation();
        checkMultiPlayerSurvivor();
        checkSinglePlayerDies();
        checkSwitchRecovers();
        checkCivNotBornYet();
        checkNoOnlinePlayersFallback();
        System.out.println();
        System.out.println("=== 结果：通过 " + pass + " 项，失败 " + fail + " 项 ===");
    }

    // ---------- Bug 1：文明分配 ----------

    private static void checkAllocation() {
        System.out.println("[Bug 1] 文明分配跳过规则");
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet, new java.util.Random(1));
        world.spawnCivilization("人类");                        // id=1 存活
        Civilization dead = world.spawnCivilization("已亡文明");  // id=2，设为灭亡
        dead.setPopulation(0);
        world.spawnCivilization("文明-3");                       // id=3 存活

        PlayerService ps = new PlayerService();
        check("第一个玩家分到 id=1", allocate(world, ps, "s1") == 1);
        check("第二个玩家跳过已灭亡的 id=2，分到 id=3", allocate(world, ps, "s2") == 3);
        check("第三个玩家跳过分完的 id，分到 id=4", allocate(world, ps, "s3") == 4);
        check("controlledCivIds 去重且含 1/3/4",
                ps.controlledCivIds().containsAll(List.of(1, 3, 4)) && ps.controlledCivIds().size() == 3);
        // 真实 sessionId 是 UUID，取「控制文明 1 的玩家」自己的 session 来验证「对自己不算被控制」
        String owner1 = ps.listOnline().stream()
                .filter(p -> p.getControlledCivId() == 1)
                .map(Player::getSessionId)
                .findFirst()
                .orElse("");
        check("isControlledByOther(1)：对别人为真、对持有者自己为假",
                !owner1.isEmpty()
                        && ps.isControlledByOther(1, "someone-else")
                        && !ps.isControlledByOther(1, owner1));
        check("无人控制的 id=5 不算被控制", !ps.isControlledByOther(5, "someone-else"));
    }

    /** 复刻 WorldController.allocateCivId 的规则（同源逻辑，仅用于离线验证）。 */
    private static int allocate(World world, PlayerService ps, String sessionId) {
        Player player = ps.join("玩家-" + sessionId);
        for (int civId = 1; civId <= 10000; civId++) {
            if (ps.isControlledByOther(civId, player.getSessionId())) {
                continue;
            }
            Civilization existing = world.findCivilization(civId);
            if (existing != null && existing.getPopulation() <= 0) {
                continue;
            }
            player.setControlledCivId(civId);
            return civId;
        }
        throw new IllegalStateException("分配失败");
    }

    // ---------- Bug 2：终局判定 ----------

    private static void checkMultiPlayerSurvivor() throws Exception {
        World world = world();
        Civilization c1 = world.getCivilizations().get(0);   // id=1
        world.spawnCivilization("文明-2");                    // id=2
        PlayerService ps = new PlayerService();
        ps.join("A").setControlledCivId(1);
        ps.join("B").setControlledCivId(2);
        EvolutionEngine engine = engine(world, ps);

        c1.setPopulation(0);   // A 的文明灭亡，B 的还活着
        engine.tick();
        check("多玩家：A 的文明灭亡、B 存活 → 游戏不结束", !engine.isGameOver());
    }

    private static void checkSinglePlayerDies() throws Exception {
        World world = world();
        Civilization c1 = world.getCivilizations().get(0);
        PlayerService ps = new PlayerService();
        ps.join("A").setControlledCivId(1);
        EvolutionEngine engine = engine(world, ps);

        c1.setPopulation(0);
        engine.tick();
        check("单玩家：自己控制的文明灭亡 → 游戏结束", engine.isGameOver());
    }

    private static void checkSwitchRecovers() throws Exception {
        World world = world();
        Civilization c1 = world.getCivilizations().get(0);
        world.spawnCivilization("文明-2");
        PlayerService ps = new PlayerService();
        Player a = ps.join("A");
        a.setControlledCivId(1);
        EvolutionEngine engine = engine(world, ps);

        c1.setPopulation(0);
        engine.tick();
        boolean overAfterDeath = engine.isGameOver();

        a.setControlledCivId(2);   // 切换到活着的文明 2
        engine.tick();
        check("切换文明后不再按旧文明判死（灭亡 → 切到存活文明 → 恢复）",
                overAfterDeath && !engine.isGameOver());
    }

    private static void checkCivNotBornYet() throws Exception {
        World world = world();
        PlayerService ps = new PlayerService();
        ps.join("A").setControlledCivId(5);   // 还没有 id=5 的文明对象
        EvolutionEngine engine = engine(world, ps);

        engine.tick();
        check("控制的 id 还没诞生（文明对象不存在）→ 不触发终局", !engine.isGameOver());
    }

    private static void checkNoOnlinePlayersFallback() throws Exception {
        World world = world();
        Civilization c1 = world.getCivilizations().get(0);
        PlayerService ps = new PlayerService();   // 没有任何在线玩家
        EvolutionEngine engine = engine(world, ps);

        c1.setPopulation(0);
        engine.tick();
        check("没有在线玩家 → 退回 id=1 旧口径（文明1 灭亡即终局）", engine.isGameOver());
    }

    // ---------- 工具 ----------

    private static World world() {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet, new java.util.Random(7));
        world.spawnCivilization("人类");   // id=1，玩家默认文明
        return world;
    }

    private static EvolutionEngine engine(World world, PlayerService ps) throws Exception {
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);
        engine.setNarrateEnabled(false);
        Field f = EvolutionEngine.class.getDeclaredField("playerService");
        f.setAccessible(true);
        f.set(engine, ps);
        return engine;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name);
        } else {
            fail++;
            System.out.println("  [失败] " + name);
        }
    }
}
