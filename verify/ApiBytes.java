import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 直接保存响应原始字节，避免控制台编码干扰。 */
public class ApiBytes {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        StringBuilder all = new StringBuilder();

        all.append("GET /api/world").append('\n');
        all.append(call(client, "GET", "/api/world", null)).append('\n');

        all.append("POST /api/world/advance {years:7}").append('\n');
        all.append(call(client, "POST", "/api/world/advance", "{\"years\":7}")).append('\n');

        all.append("GET /api/world/events").append('\n');
        all.append(call(client, "GET", "/api/world/events", null)).append('\n');

        Files.write(Path.of(args[0]), all.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("written");
    }

    private static String call(HttpClient client, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(response.body(), StandardCharsets.UTF_8);
        return response.statusCode() + " " + (text.length() > 300 ? text.substring(0, 300) + "..." : text);
    }
}
