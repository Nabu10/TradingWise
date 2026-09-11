(function () {
  var root = document.documentElement;
  var stored = localStorage.getItem("tw-theme");
  if (stored === "light" || stored === "dark") root.setAttribute("data-theme", stored);

  function toggleTheme() {
    var cur = root.getAttribute("data-theme");
    var next;
    if (cur === "dark") next = "light";
    else if (cur === "light") next = "dark";
    else next = window.matchMedia("(prefers-color-scheme: dark)").matches ? "light" : "dark";
    root.setAttribute("data-theme", next);
    localStorage.setItem("tw-theme", next);
  }

  document.querySelectorAll("[data-theme-toggle]").forEach(function (btn) {
    btn.addEventListener("click", toggleTheme);
  });

  var navToggle = document.querySelector("[data-nav-toggle]");
  var nav = document.querySelector(".nav");
  if (navToggle && nav) {
    navToggle.addEventListener("click", function () {
      var open = nav.classList.toggle("open");
      navToggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  var form = document.getElementById("waitlist-form");
  if (form) {
    form.addEventListener("submit", function (e) {
      e.preventDefault();

      var emailEl = document.getElementById("waitlist-email");
      var msg = document.getElementById("waitlist-msg");
      var email = (emailEl && emailEl.value || "").trim();

      // Let the browser's native email validation handle the common cases.
      // The server performs its own validation as the authoritative check.
      if (emailEl && !emailEl.checkValidity()) {
        if (msg) {
          msg.textContent = "Please enter a valid email address.";
          msg.className = "msg err";
        }
        if (emailEl) emailEl.focus();
        return;
      }

      if (!email) {
        if (msg) {
          msg.textContent = "Please enter your email address.";
          msg.className = "msg err";
        }
        if (emailEl) emailEl.focus();
        return;
      }

      if (msg) {
        msg.textContent = "Joining...";
        msg.className = "msg";
      }

      fetch("/api/waitlist", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: email })
      }).then(function (res) {
        return res.json().then(function (data) {
          return { ok: res.ok, data: data };
        });
      }).then(function (result) {
        if (!msg) return;

        if (result.ok) {
          msg.textContent = (result.data && result.data.message) || "You're on the list. We'll be in touch.";
          msg.className = "msg success";
          form.reset();
          try {
            var list = JSON.parse(localStorage.getItem("tw-waitlist") || "[]");
            list.push({ email: email, at: Date.now() });
            localStorage.setItem("tw-waitlist", JSON.stringify(list));
          } catch (err) {}
        } else {
          msg.textContent = (result.data && result.data.error) || "We couldn't add you right now. Please try again.";
          msg.className = "msg err";
        }
      }).catch(function () {
        if (msg) {
          msg.textContent = "Could not reach the server. Please try again in a moment.";
          msg.className = "msg err";
        }
      });
    });
  }
})();
