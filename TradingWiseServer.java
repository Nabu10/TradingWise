import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the TradingWise firm site and JSON APIs backed by
 * TradingCostPriceCalculator. Proxies Finnhub quotes so the API key
 * never reaches the browser. Accepts Pro waitlist emails (logged only).
 */
public class TradingWiseServer {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Pattern CURRENT_PRICE_FIELD = Pattern.compile("\"c\":([0-9.\\-]+)");
    private static final Pattern EMAIL_FIELD = Pattern.compile("\"email\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", TradingWiseServer::serveStatic);
        server.createContext("/api/calculate", TradingWiseServer::serveCalculate);
        server.createContext("/api/quote", TradingWiseServer::serveQuote);
        server.createContext("/api/waitlist", TradingWiseServer::serveWaitlist);
        server.setExecutor(null);
        server.start();
        System.out.println("TradingWise running on port " + port);
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "Method not allowed.");
            return;
        }

        String requestPath = exchange.getRequestURI().getPath();
        String fileName = requestPath.equals("/") ? "index.html" : requestPath.substring(1);

        // Allow nested assets/ and tools/ paths; block traversal.
        if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
            respond(exchange, 404, "text/plain", "Not found.");
            return;
        }

        boolean allowed =
                !fileName.contains("/")
                || fileName.startsWith("assets/")
                || fileName.startsWith("tools/");
        if (!allowed) {
            respond(exchange, 404, "text/plain", "Not found.");
            return;
        }

        Path path = Path.of(fileName).normalize();
        if (!path.equals(Path.of(fileName)) || fileName.isBlank()) {
            respond(exchange, 404, "text/plain", "Not found.");
            return;
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            respond(exchange, 404, "text/plain", fileName + " not found at: " + path.toAbsolutePath());
            return;
        }

        String contentType = contentTypeFor(fileName);
        byte[] body = Files.readAllBytes(path);
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.close();
            return;
        }
        respondBytes(exchange, 200, contentType, body);
    }

    private static String contentTypeFor(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void serveCalculate(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        try {
            BigDecimal buyPrice = new BigDecimal(params.get("buyPrice"));
            BigDecimal quantity = new BigDecimal(params.get("quantity"));
            BigDecimal gainPercent = new BigDecimal(params.get("gainPercent"));

            if (buyPrice.signum() <= 0 || quantity.signum() <= 0 || gainPercent.signum() <= 0) {
                respondJson(exchange, 400,
                        "{\"error\":\"buyPrice, quantity, and gainPercent must all be greater than zero\"}");
                return;
            }

            TradingCostPriceCalculator.Result r = TradingCostPriceCalculator.calculate(buyPrice, quantity, gainPercent);
            BigDecimal surplus = r.actualProceeds.subtract(r.totalCost);

            String json = "{"
                    + "\"totalCost\":" + r.totalCost + ","
                    + "\"sellPrice\":" + r.sellPrice + ","
                    + "\"sharesToSellExact\":" + r.sharesToSellExact + ","
                    + "\"sharesToSellRounded\":" + r.sharesToSellRounded + ","
                    + "\"freeSharesRemaining\":" + r.freeSharesRemaining + ","
                    + "\"actualProceeds\":" + r.actualProceeds + ","
                    + "\"surplus\":" + surplus
                    + "}";
            respondJson(exchange, 200, json);
        } catch (Exception e) {
            respondJson(exchange, 400, "{\"error\":\"Invalid input — buyPrice, quantity, and gainPercent must be numbers\"}");
        }
    }

    private static void serveQuote(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String ticker = params.get("ticker");
        if (ticker == null || ticker.isBlank()) {
            respondJson(exchange, 400, "{\"error\":\"Missing ticker\"}");
            return;
        }
        ticker = ticker.trim().toUpperCase();

        String apiKey = System.getenv("FINNHUB_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            respondJson(exchange, 500,
                    "{\"error\":\"Server is missing FINNHUB_API_KEY. Set that environment variable (get a free key at finnhub.io) and restart the server.\"}");
            return;
        }

        String url = "https://finnhub.io/api/v1/quote?symbol="
                + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                respondJson(exchange, 502, "{\"error\":\"Quote provider returned an error. Try again shortly.\"}");
                return;
            }

            Matcher matcher = CURRENT_PRICE_FIELD.matcher(response.body());
            if (!matcher.find()) {
                respondJson(exchange, 502, "{\"error\":\"Unexpected response from quote provider.\"}");
                return;
            }

            double currentPrice = Double.parseDouble(matcher.group(1));
            if (currentPrice <= 0) {
                respondJson(exchange, 404,
                        "{\"error\":\"No live price found for ticker '" + ticker + "'. Check the symbol and try again.\"}");
                return;
            }

            String json = "{\"ticker\":\"" + ticker + "\",\"currentPrice\":" + currentPrice + "}";
            respondJson(exchange, 200, json);
        } catch (Exception e) {
            respondJson(exchange, 502, "{\"error\":\"Could not reach the quote provider. Try again in a moment.\"}");
        }
    }

    private static void serveWaitlist(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }

        String body = readBody(exchange);
        String email = null;
        Matcher m = EMAIL_FIELD.matcher(body == null ? "" : body);
        if (m.find()) {
            email = m.group(1).trim();
        }
        if (email == null || email.isBlank() || !email.contains("@") || email.indexOf('@') < 1) {
            respondJson(exchange, 400, "{\"error\":\"Valid email required\"}");
            return;
        }

        System.out.println("waitlist: " + email);
        respondJson(exchange, 200, "{\"ok\":true}");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respondBytes(exchange, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        respondBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
