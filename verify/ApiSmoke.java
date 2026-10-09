import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ApiSmoke {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        StringBuilder log = new StringBuilder();

        log.append("GET /api/world -> ").append(get(client, "/api/world")).append('\n');
        log.append("POST /api/world/advance {years:50} -> ")
           .append(post(client, "/api/world/advance", "{\"years\":50}")).append('\n');
        for (int i = 1; i <= 3; i++) {
            log.append("POST /api/world/run-to-major #").append(i).append(" -> ")
               .append(post(client, "/api/world/run-to-major", "{\"maxYears\":5000}")).append('\n');
        }
        log.append("POST /api/world/speed {speed:10} -> ")
           .append(post(client, "/api/world/speed", "{\"speed\":10}")).append('\n');

        HttpResponse<byte[]> events = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/world/events")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        String body = new String(events.body(), StandardCharsets.UTF_8);
        log.append("GET /api/world/events -> ").append(events.statusCode())
           .append(" 事件数=").append(body.split("\\{\"year\"").length - 1)
           .append(" 长度=").append(body.length()).append('\n');
        log.append(body).append('\n');

        Files.write(Path.of(args[0]), log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static String get(HttpClient client, String path) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path)).GET().build());
    }

    private static String post(HttpClient client, String path, String body) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private static String send(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(response.body(), StandardCharsets.UTF_8);
        return response.statusCode() + " " + (text.length() > 300 ? text.substring(0, 300) + "..." : text);
    }
}
