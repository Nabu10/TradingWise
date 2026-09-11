(function () {
  var SYMBOLS = ["SPY", "QQQ", "DIA", "IWM", "TLT", "GLD"];
  var REFRESH_MS = 60000;

  function ensureTape() {
    var el = document.querySelector("[data-market-tape]");
    if (el) return el;
    el = document.createElement("div");
    el.className = "market-tape";
    el.setAttribute("data-market-tape", "");
    el.setAttribute("aria-label", "Market quotes");
    el.setAttribute("role", "region");
    el.innerHTML =
      '<div class="market-tape-track" data-tape-track>' +
      '<div class="market-tape-group" data-tape-group></div>' +
      "</div>";
    var header = document.querySelector(".site-header");
    if (header && header.parentNode) {
      header.parentNode.insertBefore(el, header);
    } else {
      document.body.insertBefore(el, document.body.firstChild);
    }
    return el;
  }

  function formatPrice(n) {
    if (n == null || !isFinite(n)) return "—";
    return n.toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  function formatPct(n) {
    if (n == null || !isFinite(n)) return "—";
    var sign = n > 0 ? "+" : "";
    return sign + n.toFixed(2) + "%";
  }

  function itemHtml(q) {
    var offline = !q || q.offline || q.price == null || !isFinite(q.price);
    var cls = "tape-item";
    var pct = "";
    var price = "—";
    if (!offline) {
      price = formatPrice(q.price);
      var dp = q.changePercent;
      if (dp != null && isFinite(dp)) {
        cls += dp > 0 ? " up" : dp < 0 ? " down" : " flat";
        pct = '<span class="tape-chg">' + formatPct(dp) + "</span>";
      } else {
        cls += " flat";
        pct = '<span class="tape-chg">—</span>';
      }
    } else {
      cls += " offline";
      pct = '<span class="tape-chg">—</span>';
    }
    return (
      '<span class="' +
      cls +
      '">' +
      '<span class="tape-sym">' +
      (q && q.symbol ? q.symbol : "") +
      "</span>" +
      '<span class="tape-px">' +
      price +
      "</span>" +
      pct +
      "</span>"
    );
  }

  function buildGroup(quotes, offlineNote) {
    var html = quotes.map(itemHtml).join("");
    if (offlineNote) {
      html +=
        '<span class="tape-item tape-note"><span class="tape-sym">quotes offline</span></span>';
    }
    return html;
  }

  function render(quotes, offline) {
    var root = ensureTape();
    var track = root.querySelector("[data-tape-track]");
    if (!track) return;
    var groupHtml = buildGroup(quotes, offline);
    // Duplicate groups for seamless marquee
    track.innerHTML =
      '<div class="market-tape-group" data-tape-group>' +
      groupHtml +
      "</div>" +
      '<div class="market-tape-group" aria-hidden="true">' +
      groupHtml +
      "</div>";
    root.classList.toggle("is-offline", !!offline);
  }

  function offlineQuotes() {
    return SYMBOLS.map(function (s) {
      return { symbol: s, price: null, changePercent: null, offline: true };
    });
  }

  function normalize(list) {
    var bySym = {};
    (list || []).forEach(function (q) {
      if (q && q.symbol) bySym[String(q.symbol).toUpperCase()] = q;
    });
    return SYMBOLS.map(function (s) {
      var q = bySym[s];
      if (!q) return { symbol: s, price: null, changePercent: null, offline: true };
      var price = q.price != null ? Number(q.price) : q.currentPrice != null ? Number(q.currentPrice) : null;
      var dp =
        q.changePercent != null
          ? Number(q.changePercent)
          : q.dp != null
            ? Number(q.dp)
            : null;
      var offline = !(price != null && isFinite(price) && price > 0);
      return { symbol: s, price: offline ? null : price, changePercent: dp, offline: offline };
    });
  }

  function load() {
    var url = "/api/quotes?symbols=" + encodeURIComponent(SYMBOLS.join(","));
    return fetch(url)
      .then(function (res) {
        if (!res.ok) throw new Error("bad status");
        return res.json();
      })
      .then(function (data) {
        var list = Array.isArray(data) ? data : data && data.quotes;
        var quotes = normalize(list);
        var anyLive = quotes.some(function (q) {
          return !q.offline;
        });
        render(quotes, !anyLive);
      })
      .catch(function () {
        render(offlineQuotes(), true);
      });
  }

  ensureTape();
  render(offlineQuotes(), true);
  load();
  setInterval(load, REFRESH_MS);
})();
