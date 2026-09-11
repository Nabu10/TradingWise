(function () {
  var premiumEl = document.getElementById("premium");
  var contractsEl = document.getElementById("contracts");
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

  var CONTRACT_MULTIPLIER = 100;

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
    var premiumRaw = premiumEl.value;
    var contractsRaw = contractsEl.value;
    var gainPercentRaw = gainEl.value;
    var contracts = parseFloat(contractsRaw);

    if (premiumRaw === "" || contractsRaw === "" || gainPercentRaw === "") {
      warn("Fill in a premium, contract count, and target gain to see the plan.");
      return;
    }

    var premium = parseFloat(premiumRaw);
    // cost per contract (premium × 100) stands in for buy price; contracts for quantity
    var costPerContract = premium * CONTRACT_MULTIPLIER;

    var url = "/api/calculate"
      + "?buyPrice=" + encodeURIComponent(costPerContract)
      + "&quantity=" + encodeURIComponent(contractsRaw)
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
        render(result.data, contracts);
      })
      .catch(function () {
        warn("Could not reach the TradingWise server. Make sure TradingWiseServer is running, then reload from http://localhost:8080/.");
      });
  }

  function render(r, contracts) {
    var sellPremium = Number(r.sellPrice) / CONTRACT_MULTIPLIER;
    var contractsToClose = Number(r.sharesToSellRounded);
    var freeContracts = Number(r.freeSharesRemaining);
    var totalPremiumPaid = Number(r.totalCost);

    out.sellPrice.textContent = money(sellPremium);
    out.sharesRounded.textContent = contractsToClose.toLocaleString("en-US");
    out.freeShares.textContent = freeContracts.toLocaleString("en-US");
    out.totalCost.textContent = money(totalPremiumPaid);
    out.sharesExact.textContent = Number(r.sharesToSellExact).toFixed(4);
    out.proceeds.textContent = money(Number(r.actualProceeds));
    out.surplus.textContent = money(Number(r.surplus));

    var soldPct = (contractsToClose / contracts) * 100;
    var freePct = 100 - soldPct;
    barSold.style.width = soldPct + "%";
    barFree.style.width = freePct + "%";
    labSoldPct.textContent = soldPct.toFixed(1) + "%";
    labFreePct.textContent = freePct.toFixed(1) + "%";
    barSold.textContent = soldPct > 14 ? contractsToClose + " closed" : "";
    barFree.textContent = freePct > 14 ? freeContracts + " free" : "";

    if (freeContracts === 0) {
      barCaption.textContent = "At this gain, every contract is needed to recover cost — none are left over.";
    } else {
      barCaption.textContent = "Close " + contractsToClose + " of " + contracts + " contracts at " + money(sellPremium) + " premium to recover your " + money(totalPremiumPaid) + " cost. The remaining " + freeContracts + " contracts are held at zero net cost.";
    }
  }

  [premiumEl, contractsEl, gainEl].forEach(function (el) {
    el.addEventListener("input", scheduleRecalc);
  });

  recalc();
})();
