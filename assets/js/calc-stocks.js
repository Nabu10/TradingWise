(function () {
  var buyEl = document.getElementById("buyPrice");
  var qtyEl = document.getElementById("quantity");
  var gainEl = document.getElementById("gainPercent");
  var warnEl = document.getElementById("warn");
  var resultsEl = document.getElementById("results");
  var scalingBodyEl = document.getElementById("scaling-body");

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

  function shares(n) {
    return n.toLocaleString("en-US", { maximumFractionDigits: 2 });
  }

  function warn(msg) {
    warnEl.textContent = msg;
    warnEl.classList.add("show");
    resultsEl.classList.add("hidden");
    if (scalingBodyEl) scalingBodyEl.innerHTML = "";
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
        render(result.data, quantity, parseFloat(buyPriceRaw));
      })
      .catch(function () {
        warn("Could not reach the TradingWise server. Make sure TradingWiseServer is running, then reload.");
      });
  }

  function render(r, quantity, buyPrice) {
    var sellPrice = Number(r.sellPrice);
    var sharesRounded = Number(r.sharesToSellRounded);
    var freeShares = Number(r.freeSharesRemaining);
    var totalCost = Number(r.totalCost);

    out.sellPrice.textContent = money(sellPrice);
    out.sharesRounded.textContent = shares(sharesRounded);
    out.freeShares.textContent = shares(freeShares);
    out.totalCost.textContent = money(totalCost);
    out.sharesExact.textContent = Number(r.sharesToSellExact).toFixed(4);
    out.proceeds.textContent = money(Number(r.actualProceeds));
    out.surplus.textContent = money(Number(r.surplus));

    var soldPct = quantity > 0 ? (sharesRounded / quantity) * 100 : 0;
    var freePct = Math.max(0, 100 - soldPct);
    barSold.style.width = Math.min(100, soldPct) + "%";
    barFree.style.width = freePct + "%";
    labSoldPct.textContent = soldPct.toFixed(1) + "%";
    labFreePct.textContent = freePct.toFixed(1) + "%";
    barSold.textContent = soldPct > 14 ? sharesRounded + " sold" : "";
    barFree.textContent = freePct > 14 ? freeShares + " free" : "";

    if (freeShares === 0) {
      barCaption.textContent = "At this gain, every share is needed to recover cost — none are left over.";
    } else {
      barCaption.textContent = "Sell " + sharesRounded + " of " + shares(quantity) + " shares at " + money(sellPrice) + " to recover your " + money(totalCost) + " cost. The remaining " + shares(freeShares) + " shares are held at zero net cost.";
    }

    renderScalingPlan(buyPrice, quantity, totalCost);
  }

  function renderScalingPlan(buyPrice, quantity, totalCost) {
    if (!scalingBodyEl || buyPrice <= 0 || quantity <= 0) return;

    var targets = [10, 20, 30, 50, 75, 100];
    scalingBodyEl.innerHTML = targets.map(function (gain) {
      var price = buyPrice * (1 + gain / 100);
      var sellExact = totalCost / price;
      var sellRounded = Math.min(quantity, Math.ceil(sellExact));
      var keep = Math.max(0, quantity - sellRounded);
      var keepValue = keep * price;

      return "<tr>"
        + "<td>+" + gain + "%</td>"
        + "<td>" + money(price) + "</td>"
        + "<td>" + shares(sellRounded) + "</td>"
        + "<td class=\"keep-value\">" + shares(keep) + "</td>"
        + "<td>" + money(keepValue) + "</td>"
        + "</tr>";
    }).join("");
  }

  [buyEl, qtyEl, gainEl].forEach(function (el) {
    el.addEventListener("input", scheduleRecalc);
  });

  var tickerEl = document.getElementById("ticker");
  var fetchQuoteBtn = document.getElementById("fetchQuoteBtn");
  var quoteResultEl = document.getElementById("quoteResult");
  var quoteTimer = null;

  function fetchQuote() {
    var ticker = tickerEl.value.trim().toUpperCase();
    if (!ticker) {
      quoteResultEl.textContent = "Enter a ticker first.";
      return;
    }
    tickerEl.value = ticker;
    fetchQuoteBtn.disabled = true;
    quoteResultEl.textContent = "Fetching latest quote…";

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
        if (!(price > 0)) {
          quoteResultEl.textContent = "No quote available for " + ticker + ".";
          return;
        }
        quoteResultEl.innerHTML = "<strong>" + ticker + "</strong> · " + money(price) + ' <span class="quote-source">latest quote</span> · <button type="button" class="quote-link" id="useAsBuyPrice">Use current price</button>';
        document.getElementById("useAsBuyPrice").addEventListener("click", function () {
          buyEl.value = price.toFixed(2);
          scheduleRecalc();
        });
      })
      .catch(function () {
        fetchQuoteBtn.disabled = false;
        quoteResultEl.textContent = "Network error — could not reach the quote service.";
      });
  }

  fetchQuoteBtn.addEventListener("click", fetchQuote);
  tickerEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      e.preventDefault();
      clearTimeout(quoteTimer);
      fetchQuote();
    }
  });
  tickerEl.addEventListener("input", function () {
    clearTimeout(quoteTimer);
    var ticker = tickerEl.value.trim();
    if (!ticker) {
      quoteResultEl.textContent = "";
      return;
    }
    quoteTimer = setTimeout(fetchQuote, 700);
  });

  recalc();
})();
