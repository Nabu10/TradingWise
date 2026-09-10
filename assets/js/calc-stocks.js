(function () {
  var buyEl = document.getElementById("buyPrice");
  var qtyEl = document.getElementById("quantity");
  var gainEl = document.getElementById("gainPercent");
  var warnEl = document.getElementById("warn");
  var resultsEl = document.getElementById("results");

  var out = {
    sellPrice: document.getElementById("out-sellPrice"),
    sharesRounded: document.getElementById("out-sharesRounded"),
    freeShares: document.getElementById("out-freeShares"),
    totalCost: document.getElementById("out-totalCost"),
    sharesExact: document.getElementById("out-sharesExact"),
    proceeds: document.getElementById("out-proceeds"),
    surplus: document.getElementById("out-surplus")
  };

  var barSold = document.getElementById("bar-sold");
  var barFree = document.getElementById("bar-free");
  var labSoldPct = document.getElementById("lab-sold-pct");
  var labFreePct = document.getElementById("lab-free-pct");
  var barCaption = document.getElementById("bar-caption");

  function money(n) {
    return "$" + n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function warn(msg) {
    warnEl.textContent = msg;
    warnEl.classList.add("show");
    resultsEl.classList.add("hidden");
  }

  function clearWarn() {
    warnEl.classList.remove("show");
    resultsEl.classList.remove("hidden");
  }

  var debounceTimer = null;
  function scheduleRecalc() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(recalc, 150);
  }

  function recalc() {
    var buyPriceRaw = buyEl.value;
    var quantityRaw = qtyEl.value;
    var gainPercentRaw = gainEl.value;
    var quantity = parseFloat(quantityRaw);

    if (buyPriceRaw === "" || quantityRaw === "" || gainPercentRaw === "") {
      warn("Fill in a buy price, share count, and target gain to see the plan.");
      return;
    }

    var url = "/api/calculate"
      + "?buyPrice=" + encodeURIComponent(buyPriceRaw)
      + "&quantity=" + encodeURIComponent(quantityRaw)
      + "&gainPercent=" + encodeURIComponent(gainPercentRaw);

    fetch(url)
      .then(function (res) {
        return res.json().then(function (data) { return { ok: res.ok, data: data }; });
      })
      .then(function (result) {
        if (!result.ok) {
          warn(result.data.error || "Something went wrong.");
          return;
        }
        clearWarn();
        render(result.data, quantity);
      })
      .catch(function () {
        warn("Could not reach the TradingWise server. Make sure TradingWiseServer is running, then reload from http://localhost:8080/.");
      });
  }

  function render(r, quantity) {
    var sellPrice = Number(r.sellPrice);
    var sharesRounded = Number(r.sharesToSellRounded);
    var freeShares = Number(r.freeSharesRemaining);
    var totalCost = Number(r.totalCost);

    out.sellPrice.textContent = money(sellPrice);
    out.sharesRounded.textContent = sharesRounded.toLocaleString("en-US");
    out.freeShares.textContent = freeShares.toLocaleString("en-US");
    out.totalCost.textContent = money(totalCost);
    out.sharesExact.textContent = Number(r.sharesToSellExact).toFixed(4);
    out.proceeds.textContent = money(Number(r.actualProceeds));
    out.surplus.textContent = money(Number(r.surplus));

    var soldPct = (sharesRounded / quantity) * 100;
    var freePct = 100 - soldPct;
    barSold.style.width = soldPct + "%";
    barFree.style.width = freePct + "%";
    labSoldPct.textContent = soldPct.toFixed(1) + "%";
    labFreePct.textContent = freePct.toFixed(1) + "%";
    barSold.textContent = soldPct > 14 ? sharesRounded + " sold" : "";
    barFree.textContent = freePct > 14 ? freeShares + " free" : "";

    if (freeShares === 0) {
      barCaption.textContent = "At this gain, every share is needed to recover cost — none are left over.";
    } else {
      barCaption.textContent = "Sell " + sharesRounded + " of " + quantity + " shares at " + money(sellPrice) + " to recover your " + money(totalCost) + " cost. The remaining " + freeShares + " shares are held at zero net cost.";
    }
  }

  [buyEl, qtyEl, gainEl].forEach(function (el) {
    el.addEventListener("input", scheduleRecalc);
  });

  var tickerEl = document.getElementById("ticker");
  var fetchQuoteBtn = document.getElementById("fetchQuoteBtn");
  var quoteResultEl = document.getElementById("quoteResult");

  function fetchQuote() {
    var ticker = tickerEl.value.trim();
    if (!ticker) {
      quoteResultEl.textContent = "Enter a ticker first.";
      return;
    }
    fetchQuoteBtn.disabled = true;
    quoteResultEl.textContent = "Fetching…";

    fetch("/api/quote?ticker=" + encodeURIComponent(ticker))
      .then(function (res) {
        return res.json().then(function (data) { return { ok: res.ok, data: data }; });
      })
      .then(function (result) {
        fetchQuoteBtn.disabled = false;
        if (!result.ok) {
          quoteResultEl.textContent = result.data.error || "Could not fetch price.";
          return;
        }
        var price = Number(result.data.currentPrice);
        quoteResultEl.innerHTML = "Current: $" + price.toFixed(2) + ' — <button type="button" class="quote-link" id="useAsBuyPrice">use as buy price</button>';
        document.getElementById("useAsBuyPrice").addEventListener("click", function () {
          buyEl.value = price.toFixed(2);
          scheduleRecalc();
        });
      })
      .catch(function () {
        fetchQuoteBtn.disabled = false;
        quoteResultEl.textContent = "Network error — could not reach the server.";
      });
  }

  fetchQuoteBtn.addEventListener("click", fetchQuote);
  tickerEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      e.preventDefault();
      fetchQuote();
    }
  });

  recalc();
})();
