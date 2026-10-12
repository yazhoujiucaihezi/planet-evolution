import com.planet.evolution.api.ChatController;
import com.planet.evolution.model.Civilization;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Player;
import com.planet.evolution.model.World;
import com.planet.evolution.service.PlayerService;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.PlanetProfile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;

/** 验证 ChatController 按 sessionId 解析「玩家文明」：多玩家时不再落到文明 1。 */
public class ChatPlayerResolveCheck {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet, new java.util.Random(3));
        Civilization c1 = world.spawnCivilization("人类");      // id=1 存活
        world.spawnCivilization("文明-2");                       // id=2 存活

        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);
        PlayerService players = new PlayerService();

        // 甲 → 文明1；乙 → 文明3（尚未诞生）；丙 → 未分配
        Player jia = players.join("甲");
        jia.setControlledCivId(1);
        Player yi = players.join("乙");
        yi.setControlledCivId(3);
        Player bing = players.join("丙");

        ChatController controller = new ChatController(world, engine, null, null,
                new ObjectMapper(), players);
        Method resolve = ChatController.class.getDeclaredMethod("playerCivilization", String.class);
        resolve.setAccessible(true);
        Method byName = ChatController.class.getDeclaredMethod("findCivilizationByName", String.class, long.class);
        byName.setAccessible(true);

        System.out.println("[玩家文明解析]");
        check("无 sessionId → 退回文明 1（兼容旧调用）",
                id(resolve, controller, null) == 1);
        check("空 sessionId → 退回文明 1",
                id(resolve, controller, "") == 1);
        check("未知 sessionId → 退回文明 1",
                id(resolve, controller, "not-a-real-session") == 1);
        check("甲的 sessionId → 文明 1（跟随玩家当前控制的文明）",
                id(resolve, controller, jia.getSessionId()) == 1);
        check("乙的 sessionId（控制尚未诞生的文明 3）→ 不落到文明 1",
                id(resolve, controller, yi.getSessionId()) == 0);
        check("丙的 sessionId（已登录但未分配文明）→ 不落到文明 1",
                id(resolve, controller, bing.getSessionId()) == 0);

        System.out.println();
        System.out.println("[按名字找目标文明：跳过的是「当前玩家」而不是写死的 1]");
        check("玩家是文明 1 时，能打到「文明-2」", findBy(byName, controller, "文明-2", 1L) == 2L);
        check("玩家是文明 2 时，不会把自己当目标", findBy(byName, controller, "文明-2", 2L) == null);
        check("玩家是文明 1 时，不会把自己当目标", findBy(byName, controller, "人类", 1L) == null);

        System.out.println();
        System.out.println("=== 结果：通过 " + pass + " 项，失败 " + fail + " 项 ===");
    }

    private static long id(Method resolve, ChatController controller, String sessionId) throws Exception {
        Civilization civ = (Civilization) resolve.invoke(controller, sessionId);
        return civ == null ? 0 : civ.getId();
    }

    private static Long findBy(Method byName, ChatController controller, String name, long playerCivId)
            throws Exception {
        return (Long) byName.invoke(controller, name, playerCivId);
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
