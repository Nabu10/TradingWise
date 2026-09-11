# TradingWise

Firm site and cost-recovery calculators for equities and options. Sell a calculated slice at a target gain so proceeds recover cost; hold the remainder at zero net cost.

## Run locally

Requires JDK 17+.

```bash
javac TradingCostPriceCalculator.java TradingWiseServer.java
java TradingWiseServer
```

Open [http://localhost:8080/](http://localhost:8080/).

### Environment

| Variable | Purpose |
| --- | --- |
| `PORT` | HTTP port (default `8080`) |
| `FINNHUB_API_KEY` | Optional. Enables live equity quotes via `/api/quote` ([finnhub.io](https://finnhub.io)) |

```bash
export FINNHUB_API_KEY=your_key
export PORT=8080
java TradingWiseServer
```

### Docker

```bash
docker build -t tradingwise .
docker run --rm -p 8080:8080 -e FINNHUB_API_KEY=your_key tradingwise
```

The Dockerfile compiles both `TradingCostPriceCalculator.java` and `TradingWiseServer.java`.

## Page map

| Path | Description |
| --- | --- |
| `/` / `index.html` | Marketing home |
| `tools/stocks.html` | Stocks calculator |
| `tools/options.html` | Options calculator (premium × 100) |
| `options.html` | Redirect → `tools/options.html` |
| `pricing.html` | Free vs Pro placeholders + waitlist |
| `strategy.html` | Method + $100 / 100 shares / 11% example |
| `about.html` | About |
| `disclaimer.html` | Disclaimer |

Static assets live under `assets/css`, `assets/js`, `assets/favicon.svg`.

## API

| Endpoint | Method | Notes |
| --- | --- | --- |
| `/api/calculate` | GET | Query: `buyPrice`, `quantity`, `gainPercent` |
| `/api/quote` | GET | Query: `ticker` (needs `FINNHUB_API_KEY`) |
| `/api/waitlist` | POST | JSON `{ "email": "..." }` → `{ "ok": true }` (logged to stdout) |

## Design

Institutional editorial green/gold design system in `assets/css/site.css`. Calculators call the Java engine; no duplicate math in the browser beyond options contract scaling.
