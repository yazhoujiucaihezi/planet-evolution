import com.planet.evolution.ai.AiNarrator;
import com.planet.evolution.api.ChatController;
import com.planet.evolution.mapper.WorldMapper;
import com.planet.evolution.mapper.impl.WorldMapperImpl;
import com.planet.evolution.model.Planet;
import com.planet.evolution.model.Player;
import com.planet.evolution.model.World;
import com.planet.evolution.service.PlayerService;
import com.planet.evolution.simulation.EarthLikeProfile;
import com.planet.evolution.simulation.EvolutionEngine;
import com.planet.evolution.simulation.PlanetProfile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;

/**
 * 验证「AI 回复无法解析成指令」时的新提示：
 * 不再是「AI 返回的内容无法解析为指令：…」，而是「指令失败：AI 说了：<原文前 200 字>」。
 * 用桩 AiNarrator 直接灌入非 JSON 文本，不依赖真实的本地模型。
 */
public class ParseFailMessageCheck {

    /** 桩：不管问什么都回一段非 JSON 文本（长度 304，用来同时验证截断）。 */
    static class NonJsonNarrator extends AiNarrator {
        static final String TEXT = "开头" + "中".repeat(300) + "结尾";

        @Override
        public String narrate(String prompt) {
            return TEXT;
        }
    }

    /** 桩：返回合法 JSON，用来确认正常路径没被改坏。 */
    static class JsonNarrator extends AiNarrator {
        @Override
        public String narrate(String prompt) {
            return "{\"action\":\"develop_tech\",\"scope\":\"SELF\",\"target\":\"\"}";
        }
    }

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        String prefix = "指令失败：AI 说了：";

        // --- 场景一：AI 回了非 JSON ---
        ChatController.CommandResult r1 = run(new NonJsonNarrator());
        String err = r1.error() == null ? "" : r1.error();
        System.out.println("[非 JSON 回复]");
        System.out.println("  提示 = " + err);
        check("提示以「" + prefix + "」开头", err.startsWith(prefix));
        check("回显的是 AI 原文（保留开头）", err.contains("开头"));
        String preview = err.substring(prefix.length());
        check("原文超过 200 字时截断到 200 + 省略号",
                preview.equals(NonJsonNarrator.TEXT.substring(0, 200) + "…"));
        check("预览长度 = 201", preview.length() == 201);
        check("以省略号收尾", preview.endsWith("…"));
        check("不再出现旧的「无法解析为指令」文案", !err.contains("无法解析为指令"));

        // --- 场景二：AI 回了合法 JSON（正常路径不受影响）---
        ChatController.CommandResult r2 = run(new JsonNarrator());
        System.out.println();
        System.out.println("[合法 JSON 回复]");
        System.out.println("  结果 = " + r2.result());
        check("正常指令照常执行（没有报错）", r2.error() == null && r2.result() != null);

        System.out.println();
        System.out.println("=== 结果：通过 " + pass + " 项，失败 " + fail + " 项 ===");
    }

    private static ChatController.CommandResult run(AiNarrator narrator) throws Exception {
        Planet planet = new Planet("盖亚", 15.0, 1.0);
        World world = new World(planet, new java.util.Random(11));
        world.spawnCivilization("人类");   // id=1，玩家文明
        PlanetProfile profile = new EarthLikeProfile();
        EvolutionEngine engine = new EvolutionEngine(world, profile, 20261009L);
        engine.setNarrateEnabled(false);
        PlayerService players = new PlayerService();
        Player p = players.join("甲");
        p.setControlledCivId(1);
        WorldMapper mapper = new WorldMapperImpl();

        ChatController controller = new ChatController(world, engine, narrator, mapper,
                new ObjectMapper(), players);
        Method m = ChatController.class.getDeclaredMethod("executeCommand", String.class, String.class);
        m.setAccessible(true);
        return (ChatController.CommandResult) m.invoke(controller, "发展科技", p.getSessionId());
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
