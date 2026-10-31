/* Hand-pose and swipe classifier for MediaPipe hand landmarks. Plain JS: runs in the phone/TV page and in Node tests.
 *
 * Landmarks are 21 points {x, y, z} in 0..1 image space (MediaPipe order: 0 wrist; 1-4 thumb; 5-8 index;
 * 9-12 middle; 13-16 ring; 17-20 pinky). Poses ignore hand rotation: a finger is "extended" when its tip is
 * farther from the wrist than its middle joint.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.TvClassify = factory();
})(this, function () {
  'use strict';

  var WRIST = 0;
  var FINGERS = { index: [5, 6, 8], middle: [9, 10, 12], ring: [13, 14, 16], pinky: [17, 18, 20] };
  var EXTENDED_RATIO = 1.12;      // tip must be this much farther from the wrist than the PIP joint
  var THUMB_RATIO = 1.10;
  var THUMB_VERTICAL_MIN = 0.55;  // share of the thumb's length that must point up/down for a thumbs-up/down
  var HOLD_MS = 700;
  var REPEAT_MS = 450;
  var SWIPE_WINDOW_MS = 380;
  var SWIPE_MIN_TRAVEL = 0.20;    // fraction of the frame width/height
  var SWIPE_MIN_SPEED = 0.55;     // frames per second
  var SWIPE_DOMINANCE = 1.6;
  var SWIPE_COOLDOWN_MS = 750;
  var HOLD_STILL_MAX = 0.05;      // hand centre may drift this much while holding a pose

  // Measured with the real model: an open palm scores 0.99-1.0, a relaxed, loosely curled hand 0.84-0.92.
  var STRAIGHT_MIN = 0.95;
  var FOLDED_MAX = 0.70;          // fist and thumb photos measured 0.17-0.6; anything between is a loose, resting hand

  function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }
  function dist3(a, b) { return Math.hypot(a.x - b.x, a.y - b.y, (a.z || 0) - (b.z || 0)); }

  /** 1.0 for a perfectly straight finger, lower as it bends (including bends toward the camera, which 2D misses). */
  function straightness(lm, tip) {
    var mcp = tip - 3;
    return dist3(lm[mcp], lm[tip]) / (dist3(lm[mcp], lm[mcp + 1]) + dist3(lm[mcp + 1], lm[mcp + 2]) + dist3(lm[mcp + 2], lm[tip]));
  }

  function fingerStates(lm) {
    var w = lm[WRIST], s = {};
    Object.keys(FINGERS).forEach(function (name) {
      var f = FINGERS[name];
      s[name] = dist(lm[f[2]], w) > dist(lm[f[1]], w) * EXTENDED_RATIO && straightness(lm, f[2]) >= STRAIGHT_MIN;
    });
    // The thumb folds across the palm, so compare against the pinky knuckle instead of the wrist.
    s.thumb = dist(lm[4], lm[17]) > dist(lm[3], lm[17]) * THUMB_RATIO && dist(lm[4], lm[5]) > dist(lm[3], lm[5]) * 0.9;
    return s;
  }

  /** A finger is folded when it is clearly bent (or its tip is back near the wrist); loose in between counts as neither. */
  function foldedFingers(lm) {
    var w = lm[WRIST], r = {};
    Object.keys(FINGERS).forEach(function (name) {
      var f = FINGERS[name];
      r[name] = straightness(lm, f[2]) < FOLDED_MAX || dist(lm[f[2]], w) <= dist(lm[f[1]], w);
    });
    return r;
  }

  /** One of: palm, fist, point, peace, thumbsup, thumbsdown, other. A resting, loosely curled hand is "other". */
  function pose(lm) {
    var s = fingerStates(lm), folded = foldedFingers(lm), names = ['index', 'middle', 'ring', 'pinky'];
    var four = names.filter(function (n) { return s[n]; }).length;
    var bent = names.filter(function (n) { return folded[n]; }).length;
    if (four === 4) return 'palm';
    if (four === 1 && s.index && bent === 3) return 'point';
    if (four === 2 && s.index && s.middle && bent === 2) return 'peace';
    if (four === 0 && bent === 4) {
      if (s.thumb) {
        var dy = lm[4].y - lm[2].y, len = dist(lm[4], lm[2]) || 1;
        if (-dy / len > THUMB_VERTICAL_MIN) return 'thumbsup';
        if (dy / len > THUMB_VERTICAL_MIN) return 'thumbsdown';
      }
      return 'fist';
    }
    return 'other';
  }

  /** Centre of the palm, mirrored so "right" is the user's right when the front camera is used. */
  function centre(lm, mirror) {
    var p = [lm[0], lm[5], lm[9], lm[13], lm[17]], x = 0, y = 0;
    p.forEach(function (q) { x += q.x; y += q.y; });
    x /= p.length; y /= p.length;
    return { x: mirror ? 1 - x : x, y: y };
  }

  var POSE_ACTIONS = {
    palm: { key: 'playpause', say: 'Open palm: play / pause', repeat: false },
    fist: { key: 'ok', say: 'Fist: OK', repeat: false },
    peace: { key: 'back', say: 'Peace sign: back', repeat: false },
    thumbsup: { key: 'volup', say: 'Thumbs up: volume up', repeat: true },
    thumbsdown: { key: 'voldown', say: 'Thumbs down: volume down', repeat: true }
  };
  var SWIPE_ACTIONS = {
    left: { key: 'left', say: 'Swipe left' }, right: { key: 'right', say: 'Swipe right' },
    up: { key: 'up', say: 'Swipe up' }, down: { key: 'down', say: 'Swipe down' }
  };

  /** Feed one frame at a time with update(landmarks | null, nowMs); it returns {key, say} or null. */
  function createTracker(options) {
    var mirror = !options || options.mirror !== false;
    var trail = [], lastSwipe = -1e9, held = null, heldSince = 0, firedAt = 0, fired = false, anchor = null;

    function reset() { trail = []; held = null; fired = false; anchor = null; }

    function swipe(now) {
      while (trail.length && now - trail[0].t > SWIPE_WINDOW_MS) trail.shift();
      if (trail.length < 3 || now - lastSwipe < SWIPE_COOLDOWN_MS) return null;
      var a = trail[0], b = trail[trail.length - 1], dt = (b.t - a.t) / 1000 || 1e-3;
      var dx = b.x - a.x, dy = b.y - a.y;
      var ax = Math.abs(dx), ay = Math.abs(dy);
      var dir = null;
      if (ax >= SWIPE_MIN_TRAVEL && ax / dt >= SWIPE_MIN_SPEED && ax > ay * SWIPE_DOMINANCE) dir = dx > 0 ? 'right' : 'left';
      else if (ay >= SWIPE_MIN_TRAVEL && ay / dt >= SWIPE_MIN_SPEED && ay > ax * SWIPE_DOMINANCE) dir = dy > 0 ? 'down' : 'up';
      if (!dir) return null;
      lastSwipe = now; trail = []; held = null; fired = true;
      return SWIPE_ACTIONS[dir];
    }

    function update(lm, now) {
      if (!lm) { reset(); return null; }
      var c = centre(lm, mirror), p = pose(lm);
      trail.push({ t: now, x: c.x, y: c.y });

      // A fast sideways or vertical sweep with an open or pointing hand is a swipe.
      if (p === 'palm' || p === 'point' || p === 'other') {
        var sw = swipe(now);
        if (sw) return sw;
      }

      if (!POSE_ACTIONS[p]) { held = null; fired = false; anchor = null; return null; }
      if (p !== held || (anchor && dist(c, anchor) > HOLD_STILL_MAX)) {
        held = p; heldSince = now; fired = false; anchor = c; return null;
      }
      var act = POSE_ACTIONS[p];
      if (!fired && now - heldSince >= HOLD_MS && now - lastSwipe >= SWIPE_COOLDOWN_MS) {
        fired = true; firedAt = now; return { key: act.key, say: act.say };
      }
      if (fired && act.repeat && now - firedAt >= REPEAT_MS) { firedAt = now; return { key: act.key, say: act.say }; }
      return null;
    }

    return { update: update, reset: reset, pose: pose };
  }

  return { pose: pose, fingerStates: fingerStates, createTracker: createTracker, HOLD_MS: HOLD_MS, POSE_ACTIONS: POSE_ACTIONS };
});
