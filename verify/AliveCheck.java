import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 验证 /api/world 新增计数字段（在完整响应体上检查，不做截断）。 */
public class AliveCheck {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:8081";

    public static void main(String[] args) throws Exception {
        StringBuilder log = new StringBuilder();

        String world = get("/api/world");
        log.append("初始响应（前 260 字符）:\n")
           .append(world.substring(0, Math.min(260, world.length()))).append("\n\n");

        for (int i = 0; i < 2; i++) {
            post("/api/world/run-to-major", "{\"maxYears\":5000}");
        }
        post("/api/world/advance", "{\"years\":300}");

        world = get("/api/world");
        log.append("推进后字段检查（在完整响应体上）:\n");
        String[] fields = {"\"year\"", "\"planet\"", "\"temperature\"", "\"habitability\"", "\"food\"",
                "\"resources\"", "\"population\"", "\"techTotal\"", "\"aliveCivilizations\"",
                "\"extinctCivilizations\"", "\"civilizations\"", "\"speed\"", "\"paused\""};
        for (String field : fields) {
            log.append("  ").append(field).append(" = ")
               .append(world.contains(field) ? "存在" : "缺失").append('\n');
        }

        Matcher alive = Pattern.compile("\"aliveCivilizations\":(\\d+)").matcher(world);
        Matcher extinct = Pattern.compile("\"extinctCivilizations\":(\\d+)").matcher(world);
        Matcher pop = Pattern.compile("\"population\":(\\d+)").matcher(world);
        log.append("  aliveCivilizations = ").append(alive.find() ? alive.group(1) : "未匹配").append('\n');
        log.append("  extinctCivilizations = ").append(extinct.find() ? extinct.group(1) : "未匹配").append('\n');
        log.append("  population = ").append(pop.find() ? pop.group(1) : "未匹配").append('\n');

        int entries = world.split("\"foundedYear\"", -1).length - 1;
        int zeroPop = world.split("\"population\":0,", -1).length - 1;
        log.append("  civilizations 条目数 = ").append(entries).append('\n');
        log.append("  其中 population=0 的条目数 = ").append(zeroPop).append('\n');
        Matcher aliveCheck = Pattern.compile("\"aliveCivilizations\":(\\d+)").matcher(world);
        Matcher extinctCheck = Pattern.compile("\"extinctCivilizations\":(\\d+)").matcher(world);
        boolean aliveFound = aliveCheck.find();
        boolean extinctFound = extinctCheck.find();
        log.append("  alive + extinct 是否等于条目数 = ")
           .append(aliveFound && extinctFound
                   && Integer.parseInt(aliveCheck.group(1)) + Integer.parseInt(extinctCheck.group(1)) == entries)
           .append('\n');

        Files.write(Path.of(args[0]), log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static String get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build());
    }

    private static void post(String path, String body) throws Exception {
        send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private static String send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
