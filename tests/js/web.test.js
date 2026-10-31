// Run: node --test tests/js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const base = path.join(__dirname, '..', '..', 'hub-app', 'app', 'src', 'main', 'assets', 'web');
const C = require(path.join(base, 'commands.js'));
const K = require(path.join(base, 'classify.js'));

const act = (s) => C.parse(s).actions;
const one = (s) => act(s)[0];

test('opens apps by name, with filler words and bare names', () => {
  assert.deepEqual(one('open netflix'), { type: 'app', pkg: 'com.netflix.ninja' });
  assert.deepEqual(one('Hey TV, please open YouTube!'), { type: 'app', pkg: 'com.google.android.youtube.tv' });
  assert.deepEqual(one('go to prime video'), { type: 'app', pkg: 'com.amazon.amazonvideo.livingroom' });
  assert.deepEqual(one('netflix'), { type: 'app', pkg: 'com.netflix.ninja' });
  assert.deepEqual(one('play netflix'), { type: 'app', pkg: 'com.netflix.ninja' });
  assert.deepEqual(one('open settings'), { type: 'app', pkg: 'com.mytvhome.settings' });
  assert.deepEqual(one('launch purple iptv'), { type: 'app', pkg: 'com.purple.iptv.smart.player' });
});

test('volume, mute and transport', () => {
  assert.deepEqual(one('volume up'), { type: 'key', key: 'volup', repeat: 3 });
  assert.deepEqual(one('louder'), { type: 'key', key: 'volup', repeat: 3 });
  assert.deepEqual(one('turn the volume down by 5'), { type: 'key', key: 'voldown', repeat: 5 });
  assert.deepEqual(one('volume up two'), { type: 'key', key: 'volup', repeat: 2 });
  assert.deepEqual(one('mute'), { type: 'key', key: 'mute', repeat: 1 });
  assert.deepEqual(one('pause'), { type: 'key', key: 'playpause', repeat: 1 });
  assert.deepEqual(one('play'), { type: 'key', key: 'playpause', repeat: 1 });
  assert.deepEqual(one('fast forward'), { type: 'key', key: 'forward', repeat: 1 });
  assert.deepEqual(one('turn it off'), { type: 'key', key: 'power', repeat: 1 });
});

test('navigation and selection', () => {
  assert.deepEqual(one('go home'), { type: 'key', key: 'home', repeat: 1 });
  assert.deepEqual(one('go back'), { type: 'key', key: 'back', repeat: 1 });
  assert.deepEqual(one('scroll down'), { type: 'key', key: 'down', repeat: 3 });
  assert.deepEqual(one('left'), { type: 'key', key: 'left', repeat: 1 });
  assert.deepEqual(one('move right two'), { type: 'key', key: 'right', repeat: 2 });
  assert.deepEqual(one('select'), { type: 'key', key: 'ok', repeat: 1 });
  assert.deepEqual(one('okay'), { type: 'key', key: 'ok', repeat: 1 });
});

test('search and type send text, search also presses enter', () => {
  assert.deepEqual(act('search for lofi hip hop'), [{ type: 'text', add: 'lofi hip hop' }, { type: 'key', key: 'enter', repeat: 1 }]);
  assert.deepEqual(act('search netflix'), [{ type: 'text', add: 'netflix' }, { type: 'key', key: 'enter', repeat: 1 }]);
  assert.deepEqual(act('type hello world'), [{ type: 'text', add: 'hello world' }]);
});

test('unknown or empty speech does nothing', () => {
  assert.equal(one('what is the weather like').type, 'none');
  assert.equal(one('').type, 'none');
  assert.equal(one('   ').type, 'none');
});

test('repeat counts are capped', () => {
  assert.equal(one('volume up 99').repeat, C.MAX_REPEAT);
});

// ---- synthetic hands (upright right hand, image coordinates, y grows downward) ----
const SHAPES = {
  index: { mcp: [0.45, 0.62], pip: [0.45, 0.52], ext: [[0.45, 0.46], [0.45, 0.40]], fold: [[0.46, 0.55], [0.46, 0.60]] },
  middle: { mcp: [0.50, 0.60], pip: [0.50, 0.49], ext: [[0.50, 0.42], [0.50, 0.36]], fold: [[0.50, 0.53], [0.50, 0.58]] },
  ring: { mcp: [0.55, 0.62], pip: [0.55, 0.52], ext: [[0.55, 0.46], [0.55, 0.40]], fold: [[0.54, 0.55], [0.54, 0.60]] },
  pinky: { mcp: [0.60, 0.65], pip: [0.60, 0.56], ext: [[0.60, 0.51], [0.60, 0.46]], fold: [[0.59, 0.58], [0.59, 0.63]] }
};
function hand(fingers, thumb, opts) {
  opts = opts || {};
  const lm = new Array(21);
  lm[0] = [0.5, 0.85];
  const t = { out: [[0.44, 0.80], [0.40, 0.74], [0.35, 0.68], [0.30, 0.63]],
    up: [[0.44, 0.80], [0.42, 0.72], [0.41, 0.62], [0.40, 0.52]],
    in: [[0.44, 0.80], [0.43, 0.74], [0.47, 0.68], [0.50, 0.66]] }[thumb];
  for (let i = 0; i < 4; i++) lm[1 + i] = t[i];
  [['index', 5], ['middle', 9], ['ring', 13], ['pinky', 17]].forEach(([name, i]) => {
    const s = SHAPES[name], open = fingers.includes(name);
    const [dip, tip] = open ? s.ext : s.fold;
    lm[i] = s.mcp; lm[i + 1] = s.pip; lm[i + 2] = dip; lm[i + 3] = tip;
  });
  return lm.map(([x, y]) => ({ x: x + (opts.dx || 0), y: (opts.flipY ? 1.4 - y : y) + (opts.dy || 0), z: 0 }));
}
const ALL = ['index', 'middle', 'ring', 'pinky'];

test('poses are recognised', () => {
  assert.equal(K.pose(hand(ALL, 'out')), 'palm');
  assert.equal(K.pose(hand([], 'in')), 'fist');
  assert.equal(K.pose(hand(['index'], 'in')), 'point');
  assert.equal(K.pose(hand(['index', 'middle'], 'in')), 'peace');
  assert.equal(K.pose(hand([], 'up')), 'thumbsup');
  assert.equal(K.pose(hand([], 'up', { flipY: true })), 'thumbsdown');
});

test('a relaxed, loosely curled hand is ignored (it bends toward the camera, so only depth shows it)', () => {
  const lm = hand(ALL, 'up');
  [[8, 0.12], [7, 0.05], [12, 0.12], [11, 0.05], [16, 0.12], [15, 0.05], [20, 0.12], [19, 0.05]].forEach(([i, z]) => { lm[i].z = z; });
  assert.equal(K.pose(lm), 'other');
  assert.deepEqual(run(K.createTracker({ mirror: false }), stream(lm, 0, 2400, 33)), []);
});

function run(tracker, frames) {
  const out = [];
  frames.forEach(([lm, t]) => { const r = tracker.update(lm, t); if (r) out.push(r.key); });
  return out;
}
const stream = (lm, from, to, step) => { const f = []; for (let t = from; t <= to; t += step) f.push([lm, t]); return f; };

test('holding a pose fires once; a fist is OK, a palm is play/pause', () => {
  assert.deepEqual(run(K.createTracker({ mirror: false }), stream(hand([], 'in'), 0, 1600, 33)), ['ok']);
  assert.deepEqual(run(K.createTracker({ mirror: false }), stream(hand(ALL, 'out'), 0, 1600, 33)), ['playpause']);
  assert.deepEqual(run(K.createTracker({ mirror: false }), stream(hand(['index', 'middle'], 'in'), 0, 1600, 33)), ['back']);
});

test('a short touch of a pose does nothing', () => {
  assert.deepEqual(run(K.createTracker({ mirror: false }), stream(hand([], 'in'), 0, 400, 33)), []);
});

test('thumbs up repeats volume while held', () => {
  const keys = run(K.createTracker({ mirror: false }), stream(hand([], 'up'), 0, 2400, 33));
  assert.ok(keys.length >= 3 && keys.every((k) => k === 'volup'), JSON.stringify(keys));
});

function sweep(dx, dy, ms) {
  const frames = [], n = Math.round(ms / 33);
  for (let i = 0; i <= n; i++) frames.push([hand(ALL, 'out', { dx: dx * i / n, dy: dy * i / n }), i * 33]);
  return frames;
}

test('fast sweeps are swipes in the right direction', () => {
  assert.deepEqual(run(K.createTracker({ mirror: false }), sweep(0.35, 0, 300)), ['right']);
  assert.deepEqual(run(K.createTracker({ mirror: false }), sweep(-0.35, 0, 300)), ['left']);
  assert.deepEqual(run(K.createTracker({ mirror: false }), sweep(0, 0.30, 300)), ['down']);
  assert.deepEqual(run(K.createTracker({ mirror: false }), sweep(0, -0.30, 300)), ['up']);
});

test('the front camera is mirrored, so a raw rightward sweep is the user moving left', () => {
  assert.deepEqual(run(K.createTracker({ mirror: true }), sweep(0.35, 0, 300)), ['left']);
});

test('slow drifts and diagonals are ignored', () => {
  assert.deepEqual(run(K.createTracker({ mirror: false }), sweep(0.35, 0, 2000)), []);
  assert.deepEqual(run(K.createTracker({ mirror: false }), sweep(0.30, 0.28, 300)), []);
});

test('losing the hand resets the hold', () => {
  const t = K.createTracker({ mirror: false });
  const out = run(t, stream(hand([], 'in'), 0, 500, 33).concat([[null, 600]], stream(hand([], 'in'), 700, 1000, 33)));
  assert.deepEqual(out, []);
});
