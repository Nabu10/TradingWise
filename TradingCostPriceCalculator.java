import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the TradingWise web UI and a JSON API backed by
 * TradingCostPriceCalculator, so the browser talks to the real Java
 * calculation instead of a duplicate JS copy of the math.
 */
public class TradingWiseServer {

    public static void main(String[] args) throws IOException {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", TradingWiseServer::serveIndex);
        server.createContext("/api/calculate", TradingWiseServer::serveCalculate);
        server.setExecutor(null);
        server.start();
        System.out.println("TradingWise running on port " + port);
    }

    private static void serveIndex(HttpExchange exchange) throws IOException {
        Path path = Path.of("index.html");
        if (!Files.exists(path)) {
            respond(exchange, 404, "text/plain",
                    "index.html not found. Run this from the project root, not found at: " + path.toAbsolutePath());
            return;
        }
        byte[] body = Files.readAllBytes(path);
        respondBytes(exchange, 200, "text/html", body);
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
