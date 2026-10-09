import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MajorCheck {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final StringBuilder LOG = new StringBuilder();
    private static final Pattern EVENT = Pattern.compile(
            "\\{\"year\":(-?\\d+),\"type\":\"([A-Z_]+)\",\"description\":\"(.*?)\",\"rarity\":(\\d+)\\}");

    public static void main(String[] args) throws Exception {
        log("GET /api/world");
        log(get("/api/world"));

        for (int i = 1; i <= 5; i++) {
            log("POST /api/world/run-to-major {\"maxYears\":5000}  第 " + i + " 次");
            log(post("/api/world/run-to-major", "{\"maxYears\":5000}"));
        }

        log("边界：POST /api/world/run-to-major {\"maxYears\":1}");
        log(post("/api/world/run-to-major", "{\"maxYears\":1}"));

        log("普通推进：POST /api/world/advance {\"years\":10}");
        log(post("/api/world/advance", "{\"years\":10}"));

        String events = get("/api/world/events");
        List<Integer> rarities = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = EVENT.matcher(events);
        while (matcher.find()) {
            String type = matcher.group(2);
            int rarity = Integer.parseInt(matcher.group(4));
            counts.merge(type, 1, Integer::sum);
            rarities.add(rarity);
        }
        log("GET /api/world/events 稀有度统计");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            log("  " + entry.getKey() + " = " + entry.getValue() + " 次");
        }
        long majorCount = rarities.stream().filter(r -> r >= 60).count();
        int min = rarities.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int max = rarities.stream().mapToInt(Integer::intValue).max().orElse(-1);
        log("  事件总数 = " + rarities.size() + "，稀有度范围 = " + min + "~" + max
                + "，其中 >=60（重大事件）= " + majorCount + " 条");

        log("GET /api/world 最终状态");
        log(get("/api/world"));

        Files.write(Path.of(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static void log(String line) {
        LOG.append(line).append('\n');
    }

    private static String get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path)).GET().build());
    }

    private static String post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private static String send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(response.body(), StandardCharsets.UTF_8);
        return response.statusCode() + " " + (text.length() > 500 ? text.substring(0, 500) + "..." : text);
    }
}
