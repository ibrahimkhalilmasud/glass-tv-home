// Injected into dashboard pages after they load: arrow keys move focus between links, buttons and fields
// by position, OK clicks, and the focused element gets a visible ring. Pages that handle arrow keys
// themselves (they call preventDefault) keep their own behaviour.
(function () {
  'use strict';
  if (window.__tvSpatialNav) return;
  window.__tvSpatialNav = true;

  var SELECTOR = 'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"]), [role="button"], [onclick]';
  var style = document.createElement('style');
  style.textContent = ':focus { outline: 3px solid rgba(255,255,255,0.95) !important; outline-offset: 3px !important; ' +
    'box-shadow: 0 0 0 6px rgba(0,0,0,0.45) !important; }';
  document.documentElement.appendChild(style);

  function visible(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && !el.disabled && getComputedStyle(el).visibility !== 'hidden';
  }

  function candidates() {
    return Array.prototype.filter.call(document.querySelectorAll(SELECTOR), visible);
  }

  function move(dir) {
    var list = candidates(), cur = document.activeElement;
    if (!cur || cur === document.body || list.indexOf(cur) < 0) {
      if (list[0]) { list[0].focus(); list[0].scrollIntoView({ block: 'nearest' }); }
      return true;
    }
    var a = cur.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    var best = null, bestScore = Infinity;
    list.forEach(function (el) {
      if (el === cur) return;
      var b = el.getBoundingClientRect(), dx = b.left + b.width / 2 - ax, dy = b.top + b.height / 2 - ay;
      var main = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      var cross = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      if (main <= 2) return;
      var score = main + cross * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (!best) return false;
    best.focus();
    best.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    return true;
  }

  document.addEventListener('keydown', function (e) {
    if (e.defaultPrevented) return;
    var dir = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' }[e.key];
    var el = document.activeElement, typing = el && /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName);
    if (dir) {
      if (typing && (dir === 'left' || dir === 'right')) return;
      // Nothing further in that direction: let the page scroll instead.
      if (move(dir)) e.preventDefault();
      return;
    }
    if (e.key === 'Enter' && el && el !== document.body && !typing && el.tagName !== 'A' && el.tagName !== 'BUTTON') {
      e.preventDefault();
      el.click();
    }
  }, false);
})();
