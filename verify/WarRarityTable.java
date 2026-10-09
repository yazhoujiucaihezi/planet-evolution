import com.planet.evolution.simulation.MajorEventRules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 战争稀有度对比：旧公式 85 - 总科技/20 与新公式的取值。 */
public class WarRarityTable {
    public static void main(String[] args) throws Exception {
        int[] techs = {10, 50, 100, 200, 400, 600, 800, 1000, 1200, 1500, 2000};
        StringBuilder sb = new StringBuilder();
        sb.append("阈值=").append(MajorEventRules.MAJOR_THRESHOLD).append('\n');
        sb.append("总科技\t旧公式\t新基准\t新最小(浮动0)\t新最大(浮动").append(MajorEventRules.WAR_RARITY_JITTER).append(")\n");
        for (int tech : techs) {
            int old = clamp(85 - tech / 20);
            int base = MajorEventRules.warRarityBase(tech);
            int min = MajorEventRules.warRarity(tech, 0);
            int max = MajorEventRules.warRarity(tech, MajorEventRules.WAR_RARITY_JITTER);
            sb.append(tech).append('\t').append(old).append("\t\t").append(base)
              .append('\t').append(min).append("\t\t").append(max).append('\n');
        }
        Files.write(Path.of(args[0]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
