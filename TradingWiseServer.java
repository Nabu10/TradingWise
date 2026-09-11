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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingWise web server and APIs.
 * Finnhub and Resend API keys are server-side environment variables only.
 * Waitlist confirmation is in-memory for the current testing phase.
 * While using Resend's test sender, emails are delivered to RESEND_TEST_RECIPIENT.
 */
public class TradingWiseServer {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final Pattern CURRENT_PRICE_FIELD = Pattern.compile("\\\"c\\\":([0-9.\\\\-]+)");
    private static final Pattern CHANGE_FIELD = Pattern.compile("\\\"d\\\":([0-9.\\\\-]+)");
    private static final Pattern CHANGE_PERCENT_FIELD = Pattern.compile("\\\"dp\\\":([0-9.\\\\-]+)");
    private static final Pattern PREVIOUS_CLOSE_FIELD = Pattern.compile("\\\"pc\\\":([0-9.\\\\-]+)");
    private static final Pattern VOLUME_FIELD = Pattern.compile("\\\"v\\\":([0-9.\\\\-]+)");
    private static final Pattern EMAIL_FIELD = Pattern.compile("\\\"email\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private static final Map<String, String> PENDING_CONFIRMATIONS = new ConcurrentHashMap<>();
    private static final Set<String> VERIFIED_EMAILS = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws IOException {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) port = Integer.parseInt(envPort);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", TradingWiseServer::serveStatic);
        server.createContext("/api/calculate", TradingWiseServer::serveCalculate);
        server.createContext("/api/quote", TradingWiseServer::serveQuote);
        server.createContext("/api/quotes", TradingWiseServer::serveQuotes);
        server.createContext("/api/waitlist", TradingWiseServer::serveWaitlist);
        server.createContext("/api/waitlist/confirm", TradingWiseServer::serveWaitlistConfirm);
        server.setExecutor(null);
        server.start();
        System.out.println("TradingWise running on port " + port);
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "Method not allowed."); return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        String fileName = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
        if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
            respond(exchange, 404, "text/plain", "Not found."); return;
        }
        boolean allowed = !fileName.contains("/") || fileName.startsWith("assets/") || fileName.startsWith("tools/");
        if (!allowed) { respond(exchange, 404, "text/plain", "Not found."); return; }
        Path path = Path.of(fileName).normalize();
        if (!path.equals(Path.of(fileName)) || fileName.isBlank() || !Files.exists(path) || !Files.isRegularFile(path)) {
            respond(exchange, 404, "text/plain", fileName + " not found at: " + path.toAbsolutePath()); return;
        }
        byte[] body = Files.readAllBytes(path);
        String contentType = contentTypeFor(fileName);
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length); exchange.close(); return;
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
        Map<String, String> p = parseQuery(exchange.getRequestURI().getQuery());
        try {
            BigDecimal buyPrice = new BigDecimal(p.get("buyPrice"));
            BigDecimal quantity = new BigDecimal(p.get("quantity"));
            BigDecimal gainPercent = new BigDecimal(p.get("gainPercent"));
            if (buyPrice.signum() <= 0 || quantity.signum() <= 0 || gainPercent.signum() <= 0) {
                respondJson(exchange, 400, "{\"error\":\"buyPrice, quantity, and gainPercent must all be greater than zero\"}"); return;
            }
            TradingCostPriceCalculator.Result r = TradingCostPriceCalculator.calculate(buyPrice, quantity, gainPercent);
            BigDecimal surplus = r.actualProceeds.subtract(r.totalCost);
            String json = "{\"totalCost\":" + r.totalCost + ",\"sellPrice\":" + r.sellPrice
                    + ",\"sharesToSellExact\":" + r.sharesToSellExact + ",\"sharesToSellRounded\":" + r.sharesToSellRounded
                    + ",\"freeSharesRemaining\":" + r.freeSharesRemaining + ",\"actualProceeds\":" + r.actualProceeds
                    + ",\"surplus\":" + surplus + "}";
            respondJson(exchange, 200, json);
        } catch (Exception e) {
            respondJson(exchange, 400, "{\"error\":\"Invalid input — buyPrice, quantity, and gainPercent must be numbers\"}");
        }
    }

    private static void serveQuote(HttpExchange exchange) throws IOException {
        String ticker = parseQuery(exchange.getRequestURI().getQuery()).get("ticker");
        if (ticker == null || ticker.isBlank()) { respondJson(exchange, 400, "{\"error\":\"Missing ticker\"}"); return; }
        ticker = ticker.trim().toUpperCase();
        String apiKey = System.getenv("FINNHUB_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            respondJson(exchange, 500, "{\"error\":\"Server is missing FINNHUB_API_KEY. Set that environment variable and restart the server.\"}"); return;
        }
        try {
            QuoteData q = parseQuote(fetchQuoteBody(ticker, apiKey));
            if (q.currentPrice == null || q.currentPrice <= 0) {
                respondJson(exchange, 404, "{\"error\":\"No quote found for ticker '" + ticker + "'. Check the symbol and try again.\"}"); return;
            }
            respondJson(exchange, 200, "{\"ticker\":\"" + ticker + "\",\"currentPrice\":" + q.currentPrice
                    + ",\"change\":" + numberOrNull(q.change) + ",\"changePercent\":" + numberOrNull(q.changePercent)
                    + ",\"previousClose\":" + numberOrNull(q.previousClose) + ",\"volume\":" + numberOrNull(q.volume) + "}");
        } catch (Exception e) { respondJson(exchange, 502, "{\"error\":\"Could not reach the quote provider. Try again in a moment.\"}"); }
    }

    private static void serveQuotes(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{\"error\":\"GET required\"}"); return;
        }
        String symbolsParam = parseQuery(exchange.getRequestURI().getQuery()).get("symbols");
        if (symbolsParam == null || symbolsParam.isBlank()) symbolsParam = "SPY,QQQ,DIA,IWM,TLT,GLD";
        String apiKey = System.getenv("FINNHUB_API_KEY");
        StringBuilder json = new StringBuilder("["); boolean any = false;
        for (String raw : symbolsParam.split(",")) {
            String symbol = raw.trim().toUpperCase(); if (symbol.isEmpty()) continue;
            if (any) json.append(","); any = true;
            if (apiKey == null || apiKey.isBlank()) { appendOfflineQuote(json, symbol); continue; }
            try {
                QuoteData q = parseQuote(fetchQuoteBody(symbol, apiKey));
                if (q.currentPrice == null || q.currentPrice <= 0) appendOfflineQuote(json, symbol);
                else json.append("{\"symbol\":\"").append(symbol).append("\",\"price\":").append(q.currentPrice)
                        .append(",\"change\":").append(numberOrNull(q.change)).append(",\"changePercent\":").append(numberOrNull(q.changePercent))
                        .append(",\"previousClose\":").append(numberOrNull(q.previousClose)).append(",\"volume\":").append(numberOrNull(q.volume)).append(",\"offline\":false}");
            } catch (Exception e) { appendOfflineQuote(json, symbol); }
        }
        json.append("]"); respondJson(exchange, 200, json.toString());
    }

    private static String fetchQuoteBody(String symbol, String apiKey) throws IOException, InterruptedException {
        String url = "https://finnhub.io/api/v1/quote?symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpRequest.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Quote provider returned HTTP " + response.statusCode());
        return response.body();
    }

    private static QuoteData parseQuote(String body) {
        QuoteData q = new QuoteData(); q.currentPrice = findNumber(CURRENT_PRICE_FIELD, body); q.change = findNumber(CHANGE_FIELD, body);
        q.changePercent = findNumber(CHANGE_PERCENT_FIELD, body); q.previousClose = findNumber(PREVIOUS_CLOSE_FIELD, body); q.volume = findNumber(VOLUME_FIELD, body); return q;
    }
    private static Double findNumber(Pattern pattern, String body) { Matcher m = pattern.matcher(body); return m.find() ? Double.parseDouble(m.group(1)) : null; }
    private static String numberOrNull(Double value) { return value == null || value.isNaN() ? "null" : value.toString(); }
    private static void appendOfflineQuote(StringBuilder json, String symbol) {
        json.append("{\"symbol\":\"").append(symbol).append("\",\"price\":null,\"change\":null,\"changePercent\":null,\"previousClose\":null,\"volume\":null,\"offline\":true}");
    }
    private static final class QuoteData { Double currentPrice, change, changePercent, previousClose, volume; }

    private static void serveWaitlist(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { respondJson(exchange, 405, "{\"error\":\"POST required\"}"); return; }
        String body = readBody(exchange);
        Matcher m = EMAIL_FIELD.matcher(body == null ? "" : body);
        if (!m.find()) { respondJson(exchange, 400, "{\"error\":\"Please enter a valid email address.\"}"); return; }
        String email = m.group(1).trim().toLowerCase();
        if (!isValidEmail(email)) { respondJson(exchange, 400, "{\"error\":\"Please enter a valid email address.\"}"); return; }
        if (VERIFIED_EMAILS.contains(email)) { respondJson(exchange, 200, "{\"ok\":true,\"verified\":true,\"message\":\"This email is already on the waitlist.\"}"); return; }

        String resendKey = System.getenv("RESEND_API_KEY");
        String testRecipient = System.getenv("RESEND_TEST_RECIPIENT");
        if (resendKey == null || resendKey.isBlank() || testRecipient == null || testRecipient.isBlank()) {
            respondJson(exchange, 500, "{\"error\":\"Waitlist email service is not configured yet.\"}"); return;
        }
        if (!isValidEmail(testRecipient)) {
            respondJson(exchange, 500, "{\"error\":\"RESEND_TEST_RECIPIENT is not a valid email address.\"}"); return;
        }
        String token = UUID.randomUUID().toString();
        PENDING_CONFIRMATIONS.put(token, email);
        String confirmUrl = baseUrl(exchange) + "/api/waitlist/confirm?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            sendResendEmail(resendKey, testRecipient, "Confirm your TradingWise Pro waitlist", confirmationHtml(email, confirmUrl));
            System.out.println("waitlist confirmation sent to test recipient for: " + email);
            respondJson(exchange, 200, "{\"ok\":true,\"pending\":true,\"testing\":true,\"message\":\"Confirmation email sent to the TradingWise test inbox.\"}");
        } catch (Exception e) {
            PENDING_CONFIRMATIONS.remove(token);
            System.err.println("waitlist email failed: " + e.getMessage());
            respondJson(exchange, 502, "{\"error\":\"We could not send the confirmation email. Please try again.\"}");
        }
    }

    private static void serveWaitlistConfirm(HttpExchange exchange) throws IOException {
        String token = parseQuery(exchange.getRequestURI().getQuery()).get("token");
        if (token == null || token.isBlank()) { respondHtml(exchange, 400, confirmationPage("Invalid confirmation link", "This confirmation link is missing its token.")); return; }
        String email = PENDING_CONFIRMATIONS.remove(token);
        if (email == null) { respondHtml(exchange, 400, confirmationPage("Link expired", "This confirmation link is invalid or has already been used.")); return; }
        VERIFIED_EMAILS.add(email);
        String resendKey = System.getenv("RESEND_API_KEY");
        String testRecipient = System.getenv("RESEND_TEST_RECIPIENT");
        if (resendKey != null && !resendKey.isBlank() && testRecipient != null && isValidEmail(testRecipient)) {
            try { sendResendEmail(resendKey, testRecipient, "You're on the TradingWise Pro waitlist", welcomeHtml(email)); }
            catch (Exception e) { System.err.println("waitlist welcome email failed: " + e.getMessage()); }
        }
        System.out.println("waitlist verified: " + email);
        respondHtml(exchange, 200, confirmationPage("You're on the TradingWise Pro waitlist", "Your email is confirmed. We'll be in touch when TradingWise Pro is ready."));
    }

    private static void sendResendEmail(String apiKey, String to, String subject, String html) throws IOException, InterruptedException {
        String json = "{\"from\":\"TradingWise <onboarding@resend.dev>\",\"to\":[\"" + jsonEscape(to) + "\"],\"subject\":\"" + jsonEscape(subject) + "\",\"html\":\"" + jsonEscape(html) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Resend HTTP " + response.statusCode() + ": " + response.body());
    }

    private static String confirmationHtml(String email, String url) {
        return "<div style='font-family:Arial,sans-serif;max-width:560px;margin:auto;padding:32px;color:#222'>"
                + "<h1>Confirm your TradingWise Pro waitlist</h1><p>Thanks for your interest in TradingWise Pro.</p>"
                + "<p>Click below to confirm this email address and join the waitlist.</p>"
                + "<p><a href='" + htmlEscape(url) + "' style='display:inline-block;padding:12px 18px;background:#111;color:#fff;text-decoration:none;border-radius:6px'>Confirm my email</a></p>"
                + "<p style='color:#666'>No payment is required. This is interest only.</p><p style='color:#666'>TradingWise</p></div>";
    }
    private static String welcomeHtml(String email) {
        return "<div style='font-family:Arial,sans-serif;max-width:560px;margin:auto;padding:32px;color:#222'>"
                + "<h1>You're on the TradingWise Pro waitlist</h1><p>Your email is confirmed and you're officially on the list.</p>"
                + "<p>We're working toward saved trading plans, price alerts, and more powerful position-management tools.</p>"
                + "<p>We'll let you know when Pro is ready.</p><p>— TradingWise</p></div>";
    }
    private static String confirmationPage(String title, String message) {
        return "<!doctype html><html><head><meta charset='utf-8'><title>TradingWise</title></head><body style='font-family:Arial,sans-serif;padding:48px;text-align:center'><h1>"
                + htmlEscape(title) + "</h1><p>" + htmlEscape(message) + "</p><p><a href='/pricing.html'>Return to TradingWise</a></p></body></html>";
    }
    private static String baseUrl(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) proto = "http";
        if (host == null || host.isBlank()) host = "localhost:8080";
        return proto + "://" + host;
    }
    private static boolean isValidEmail(String email) {
        if (email == null || email.length() > 254 || email.isBlank() || email.contains("..")) return false;
        int at = email.indexOf('@');
        if (at < 1 || at != email.lastIndexOf('@') || at == email.length() - 1) return false;
        String local = email.substring(0, at), domain = email.substring(at + 1);
        if (local.length() > 64 || domain.length() < 3 || !domain.contains(".")) return false;
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) return false;
        for (char c : local.toCharArray()) if (!(Character.isLetterOrDigit(c) || "!#$%&'*+-/=?^_`{|}~.".indexOf(c) >= 0)) return false;
        for (char c : domain.toCharArray()) if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-')) return false;
        for (String label : domain.split("\\.")) if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) return false;
        return true;
    }
    private static String jsonEscape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n"); }
    private static String htmlEscape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String readBody(HttpExchange exchange) throws IOException { try (InputStream in = exchange.getRequestBody()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); } }
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>(); if (query == null) return result;
        for (String pair : query.split("&")) { int eq = pair.indexOf('='); if (eq < 0) continue;
            result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)); }
        return result;
    }
    private static void respondJson(HttpExchange e, int status, String json) throws IOException { respondBytes(e, status, "application/json", json.getBytes(StandardCharsets.UTF_8)); }
    private static void respondHtml(HttpExchange e, int status, String html) throws IOException { respondBytes(e, status, "text/html", html.getBytes(StandardCharsets.UTF_8)); }
    private static void respond(HttpExchange e, int status, String contentType, String body) throws IOException { respondBytes(e, status, contentType, body.getBytes(StandardCharsets.UTF_8)); }
    private static void respondBytes(HttpExchange e, int status, String contentType, byte[] body) throws IOException {
        e.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8"); e.sendResponseHeaders(status, body.length);
        try (OutputStream os = e.getResponseBody()) { os.write(body); }
    }
}
