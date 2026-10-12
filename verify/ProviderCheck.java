import java.lang.reflect.Field;

/** 反射打印 AiNarrator 的四个 AI 配置常量，验证环境变量切换是否生效。 */
public class ProviderCheck {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.planet.evolution.ai.AiNarrator");
        System.out.println("AI_PROVIDER 环境变量 = " + System.getenv("AI_PROVIDER"));
        for (String name : new String[] { "PROVIDER", "API_URL", "API_KEY", "MODEL" }) {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            System.out.println("  " + name + " = " + f.get(null));
        }
    }
}
