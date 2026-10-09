import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class EventsDump {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        StringBuilder log = new StringBuilder();

        for (int i = 1; i <= 3; i++) {
            HttpResponse<byte[]> r = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/world/run-to-major"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"maxYears\":5000}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            log.append("run-to-major #").append(i).append(" -> ").append(r.statusCode()).append('\n');
        }

        HttpResponse<byte[]> events = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/world/events")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        String body = new String(events.body(), StandardCharsets.UTF_8);
        log.append("GET /api/world/events -> ").append(events.statusCode())
           .append("  长度=").append(body.length()).append('\n');
        log.append(body).append('\n');

        Files.write(Path.of(args[0]), log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }
}
