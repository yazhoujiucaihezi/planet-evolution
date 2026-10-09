import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class ApiCheck {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        get("/api/world");
        post("/api/world/advance", "{\"years\":50}");
        get("/api/world");
        get("/api/world/events");
        post("/api/world/advance", "{\"years\":99999}");
        get("/api/world");
        post("/api/world/speed", "{\"speed\":50}");
        post("/api/world/pause", "{}");
        post("/api/world/resume", "{}");
        post("/api/world/speed", "{\"speed\":999}");
        post("/api/world/advance", "{\"years\":0}");
        get("/api/world");
    }

    private static void get(String path) throws Exception {
        send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path)).GET().build(), path);
    }

    private static void post(String path, String body) throws Exception {
        send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), path);
    }

    private static void send(HttpRequest request, String path) throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(response.body(), StandardCharsets.UTF_8);
        if (text.length() > 420) {
            text = text.substring(0, 420) + " ...(截断)";
        }
        System.out.println(">>> " + path + " -> " + response.statusCode());
        System.out.println(text);
        System.out.println();
    }
}
