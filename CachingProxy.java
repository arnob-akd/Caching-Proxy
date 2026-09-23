import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CachingProxy {

    static String origin;

    static final String CLEAR_CACHE_PATH = "/__clear_cache__";

    static final Path PORT_FILE =
            Path.of(System.getProperty("user.home"), ".caching-proxy-port");

    static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static final Map<String, CachedResponse> cache =
            new ConcurrentHashMap<>();

    static final Set<String> BLOCKED_HEADERS = Set.of(
            "content-length",
            "transfer-encoding",
            "connection"
    );

    static class CachedResponse {

        byte[] body;
        int statusCode;
        Map<String, List<String>> headers;

        CachedResponse(
                byte[] body,
                int statusCode,
                Map<String, List<String>> headers
        ) {
            this.body = body;
            this.statusCode = statusCode;
            this.headers = headers;
        }
    }

    public static void main(String[] args)
            throws IOException, InterruptedException {

        int port = -1;
        boolean clearCacheMode = false;

        for (int i = 0; i < args.length; i++) {

            if (args[i].equals("--port")) {

                if (i + 1 >= args.length) {
                    System.out.println("Error: --port requires a number.");
                    return;
                }

                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.out.println("Error: Port must be a number.");
                    return;
                }

            }

            else if (args[i].equals("--origin")) {

                if (i + 1 >= args.length) {
                    System.out.println("Error: --origin requires a URL.");
                    return;
                }

                origin = args[++i];

            }

            else if (args[i].equals("--clear-cache")) {

                clearCacheMode = true;

            }

            else {

                System.out.println(
                        "Unknown argument: " + args[i]
                );

                printUsage();
                return;
            }
        }

        if (clearCacheMode) {

            if (port == -1) {

                if (!Files.exists(PORT_FILE)) {

                    System.out.println(
                            "Could not find a running caching proxy server."
                    );

                    return;
                }

                try {

                    String savedPort =
                            Files.readString(PORT_FILE).trim();

                    port = Integer.parseInt(savedPort);

                } catch (Exception e) {

                    System.out.println(
                            "Could not determine the proxy server port."
                    );

                    return;
                }
            }

            sendClearCacheRequest(port);
            return;
        }

        if (port == -1 || origin == null) {

            System.out.println(
                    "Error: --port and --origin are required."
            );

            printUsage();
            return;
        }

        if (port < 1 || port > 65535) {

            System.out.println(
                    "Error: Port must be between 1 and 65535."
            );

            return;
        }

        while (origin.endsWith("/")) {
            origin = origin.substring(
                    0,
                    origin.length() - 1
            );
        }

        startServer(port);
    }

    static void startServer(int port)
            throws IOException {

        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(port),
                        0
                );

        Files.writeString(
                PORT_FILE,
                String.valueOf(port)
        );

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(() -> {

                            try {
                                Files.deleteIfExists(PORT_FILE);
                            } catch (IOException ignored) {
                            }

                        })
                );

        server.createContext(
                "/",
                exchange -> handleRequest(exchange)
        );

        server.setExecutor(null);

        server.start();

        System.out.println(
                "Caching proxy server started on port "
                        + port
        );

        System.out.println(
                "Forwarding to "
                        + origin
        );
    }

    static void handleRequest(HttpExchange exchange)
            throws IOException {

        String path =
                exchange.getRequestURI().toString();

        if (exchange.getRequestURI()
                .getPath()
                .equals(CLEAR_CACHE_PATH)) {

            handleClearCache(exchange);
            return;
        }

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("GET")) {

            String message =
                    "Only GET requests are supported.";

            byte[] body =
                    message.getBytes();

            exchange.sendResponseHeaders(
                    405,
                    body.length
            );

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(body);
            }

            return;
        }

        CachedResponse cachedResponse =
                cache.get(path);

        if (cachedResponse != null) {

            copyHeaders(
                    cachedResponse.headers,
                    exchange
            );

            exchange.getResponseHeaders()
                    .set(
                            "X-Cache",
                            "HIT"
                    );

            exchange.sendResponseHeaders(
                    cachedResponse.statusCode,
                    cachedResponse.body.length
            );

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(
                        cachedResponse.body
                );
            }

            return;
        }

        try {

            URI targetUri =
                    URI.create(
                            origin + path
                    );

            HttpRequest forwardRequest =
                    HttpRequest.newBuilder()
                            .uri(targetUri)
                            .GET()
                            .build();

            HttpResponse<byte[]> response =
                    client.send(
                            forwardRequest,
                            HttpResponse
                                    .BodyHandlers
                                    .ofByteArray()
                    );

            Map<String, List<String>>
                    responseHeaders =
                    copyHeaderMap(
                            response.headers().map()
                    );

            CachedResponse cached =
                    new CachedResponse(
                            response.body(),
                            response.statusCode(),
                            responseHeaders
                    );

            cache.put(
                    path,
                    cached
            );

            copyHeaders(
                    responseHeaders,
                    exchange
            );

            exchange.getResponseHeaders()
                    .set(
                            "X-Cache",
                            "MISS"
                    );

            exchange.sendResponseHeaders(
                    response.statusCode(),
                    response.body().length
            );

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(
                        response.body()
                );
            }

        }

        catch (Exception e) {

            String message =
                    "Bad Gateway: "
                            + e.getMessage();

            byte[] body =
                    message.getBytes();

            exchange.sendResponseHeaders(
                    502,
                    body.length
            );

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(body);
            }
        }
    }

    static void handleClearCache(
            HttpExchange exchange
    ) throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("DELETE")) {

            String message =
                    "Method Not Allowed";

            byte[] body =
                    message.getBytes();

            exchange.sendResponseHeaders(
                    405,
                    body.length
            );

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(body);
            }

            return;
        }

        cache.clear();

        String message =
                "Cache cleared";

        byte[] body =
                message.getBytes();

        exchange.sendResponseHeaders(
                200,
                body.length
        );

        try (OutputStream os =
                     exchange.getResponseBody()) {

            os.write(body);
        }
    }

    static void sendClearCacheRequest(
            int port
    ) {

        try {

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "http://localhost:"
                                                    + port
                                                    + CLEAR_CACHE_PATH
                                    )
                            )
                            .DELETE()
                            .build();

            HttpResponse<String> response =
                    client.send(
                            request,
                            HttpResponse
                                    .BodyHandlers
                                    .ofString()
                    );

            System.out.println(
                    response.body()
            );

        }

        catch (Exception e) {

            System.out.println(
                    "Could not reach the server on port "
                            + port
                            + ". Is it running?"
            );
        }
    }

    static Map<String, List<String>>
    copyHeaderMap(
            Map<String, List<String>> original
    ) {

        Map<String, List<String>>
                copied =
                new HashMap<>();

        for (
                Map.Entry<String, List<String>>
                        entry :
                original.entrySet()
        ) {

            String headerName =
                    entry.getKey();

            if (BLOCKED_HEADERS.contains(
                    headerName.toLowerCase()
            )) {
                continue;
            }

            copied.put(
                    headerName,
                    List.copyOf(
                            entry.getValue()
                    )
            );
        }

        return copied;
    }

    static void copyHeaders(
            Map<String, List<String>> headers,
            HttpExchange exchange
    ) {

        for (
                Map.Entry<String, List<String>>
                        entry :
                headers.entrySet()
        ) {

            String headerName =
                    entry.getKey();

            if (BLOCKED_HEADERS.contains(
                    headerName.toLowerCase()
            )) {
                continue;
            }

            for (
                    String value :
                    entry.getValue()
            ) {

                exchange
                        .getResponseHeaders()
                        .add(
                                headerName,
                                value
                        );
            }
        }
    }

    static void printUsage() {

        System.out.println();

        System.out.println(
                "Start proxy:"
        );

        System.out.println(
                "java -jar CachingProxy.jar "
                        + "--port <port> "
                        + "--origin <url>"
        );

        System.out.println();

        System.out.println(
                "Clear cache:"
        );

        System.out.println(
                "java -jar CachingProxy.jar "
                        + "--clear-cache"
        );
    }
}