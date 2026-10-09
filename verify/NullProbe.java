import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 只读诊断：在 50000 年、上万条事件规模下，统计 /api/world/events
 * 原始响应体里的 null 元素数量与出现位置，不修改任何项目文件。
 */
public class NullProbe {
    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:" + args[0];
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        StringBuilder log = new StringBuilder();

        // 1) 通过 advance 推进（后端把它切成多次，避免单次超时）
        int total = 0;
        for (int i = 0; i < 5; i++) {
            String body = "{\"years\":10000}";
            HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(base + "/api/world/advance"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String text = new String(r.body(), StandardCharsets.UTF_8);
            log.append("advance #").append(i + 1).append(" -> ").append(r.statusCode())
               .append(' ').append(text).append('\n');
            total += 10000;
        }

        // 2) 流式统计 /api/world/events 的 null 元素
        HttpResponse<InputStream> resp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/world/events"))
                        .timeout(java.time.Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        long bytes = 0;
        long elements = 0;
        long nulls = 0;
        StringBuilder context = new StringBuilder();
        int prev = -1;
        try (InputStream in = resp.body()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    int c = buf[i] & 0xFF;
                    bytes++;
                    if (c == '{') {
                        elements++;
                    } else if (c == 'n' && prev == -1) {
                        // 检测裸 null 元素：形如 ,null, 或 [null,
                        prev = c;
                    }
                    if (c == 'n') {
                        // 记录 n 的位置，后续判断
                    }
                    // 简化：用滑动窗口匹配 "null" 且前后为 , 或 [ ]
                    if (c == 'n') {
                        pendingNull = 4;
                    } else if (pendingNull > 0) {
                        if ("null".charAt(4 - pendingNull) == (char) c) {
                            pendingNull--;
                            if (pendingNull == 0) {
                                nulls++;
                                context.append("命中 null @byte").append(bytes - 4).append(' ');
                            }
                        } else {
                            pendingNull = 0;
                        }
                    }
                    prev = c;
                }
            }
        }

        log.append("\n响应状态: ").append(resp.statusCode()).append('\n');
        log.append("响应体字节数: ").append(bytes).append('\n');
        log.append("'{' 出现次数(约等于元素数): ").append(elements).append('\n');
        log.append("裸 null 出现次数: ").append(nulls).append('\n');
        log.append("null 命中上下文: ").append(context).append('\n');

        // 3) 再查一次 /api/world 看世界规模
        String world = send(client, base + "/api/world");
        log.append("world 摘要: ")
           .append(world.substring(0, Math.min(300, world.length()))).append('\n');

        Files.write(Path.of(args[1]), log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static int pendingNull = 0;

    private static String send(HttpClient client, String url) throws Exception {
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new String(r.body(), StandardCharsets.UTF_8);
    }
}
