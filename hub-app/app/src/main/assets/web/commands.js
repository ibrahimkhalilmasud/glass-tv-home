/* Turns a spoken or typed sentence into TV actions. Plain JS so it runs in the phone page and in Node tests.
 *
 *   TvCommands.parse("open netflix")  ->  { actions: [{type:"app", pkg:"com.netflix.ninja"}], say: "Opening Netflix" }
 *
 * Actions: {type:"key", key:"volup", repeat:3} | {type:"app", pkg} | {type:"text", add} | {type:"none"}
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.TvCommands = factory();
})(this, function () {
  'use strict';

  var APPS = [
    ['prime video', 'com.amazon.amazonvideo.livingroom', 'Prime Video'],
    ['amazon prime', 'com.amazon.amazonvideo.livingroom', 'Prime Video'],
    ['prime', 'com.amazon.amazonvideo.livingroom', 'Prime Video'],
    ['purple iptv', 'com.purple.iptv.smart.player', 'Purple IPTV'],
    ['purple', 'com.purple.iptv.smart.player', 'Purple IPTV'],
    ['live channels', 'com.google.android.tv', 'Live Channels'],
    ['live tv', 'com.google.android.tv', 'Live Channels'],
    ['play store', 'com.android.vending', 'Play Store'],
    ['google play', 'com.android.vending', 'Play Store'],
    ['disney plus hotstar', 'in.startv.hotstar', 'Hotstar'],
    ['disney hotstar', 'in.startv.hotstar', 'Hotstar'],
    ['hotstar', 'in.startv.hotstar', 'Hotstar'],
    ['disney', 'in.startv.hotstar', 'Hotstar'],
    ['youtube', 'com.google.android.youtube.tv', 'YouTube'],
    ['you tube', 'com.google.android.youtube.tv', 'YouTube'],
    ['netflix', 'com.netflix.ninja', 'Netflix'],
    ['tv hub', 'com.mytvhome.hub', 'TV Hub'],
    ['hub', 'com.mytvhome.hub', 'TV Hub'],
    ['settings', 'com.mytvhome.settings', 'Settings'],
    ['setting', 'com.mytvhome.settings', 'Settings'],
    ['vlc', 'org.videolan.vlc', 'VLC'],
    ['iptv', 'com.poster.iptv.android', 'IPTV']
  ];

  var NUMBERS = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, once: 1, twice: 2 };
  var MAX_REPEAT = 20;
  var DEFAULT_VOLUME_STEPS = 3;
  var DEFAULT_SCROLL_STEPS = 3;
  var FILLERS = /\b(please|hey tv|ok tv|okay tv|tv|could you|can you|would you|just|kindly|now|for me)\b/g;

  function normalise(raw) {
    return String(raw || '').toLowerCase().replace(/[.,!?;:]/g, ' ').replace(/\bthe\b/g, ' ')
      .replace(FILLERS, ' ').replace(/\s+/g, ' ').trim();
  }

  function count(text, fallback) {
    var m = /\b(\d{1,2})\b/.exec(text);
    if (m) return Math.min(parseInt(m[1], 10), MAX_REPEAT);
    var words = text.split(' ');
    for (var i = 0; i < words.length; i++) if (NUMBERS[words[i]]) return Math.min(NUMBERS[words[i]], MAX_REPEAT);
    return fallback;
  }

  function findApp(text) {
    for (var i = 0; i < APPS.length; i++) {
      if (new RegExp('(^| )' + APPS[i][0] + '( |$)').test(text)) return APPS[i];
    }
    return null;
  }

  function key(k, repeat, say) {
    return { actions: [{ type: 'key', key: k, repeat: repeat || 1 }], say: say };
  }

  function parse(raw) {
    var t = normalise(raw), m;
    if (!t) return { actions: [{ type: 'none' }], say: 'I did not hear anything' };

    // Text entry first, so what follows "search" or "type" is never read as a command.
    if ((m = /^(?:search for|search|look for|find) (.+)$/.exec(t))) {
      return { actions: [{ type: 'text', add: m[1] }, { type: 'key', key: 'enter', repeat: 1 }], say: 'Searching for ' + m[1] };
    }
    if ((m = /^(?:type|write|say) (.+)$/.exec(t))) {
      return { actions: [{ type: 'text', add: m[1] }], say: 'Typing ' + m[1] };
    }

    // Opening an app: "open netflix", "go to youtube", "play netflix", or just the app's name.
    if ((m = /^(?:open|launch|start|go to|switch to|show|watch|play|run) (.+)$/.exec(t))) {
      var a = findApp(m[1]);
      if (a) return { actions: [{ type: 'app', pkg: a[1] }], say: 'Opening ' + a[2] };
    }
    var bare = findApp(t);
    if (bare && t.split(' ').length <= 3) return { actions: [{ type: 'app', pkg: bare[1] }], say: 'Opening ' + bare[2] };

    if (/\bmute\b/.test(t)) return key('mute', 1, 'Toggling mute');
    if (/\b(volume|sound)\b.*\b(up|increase|raise|higher|louder)\b|\b(louder|turn it up|turn up|increase volume|raise volume)\b/.test(t)) {
      return key('volup', count(t, DEFAULT_VOLUME_STEPS), 'Volume up');
    }
    if (/\b(volume|sound)\b.*\b(down|decrease|lower|reduce|quieter|softer)\b|\b(quieter|softer|turn it down|turn down|decrease volume|lower volume)\b/.test(t)) {
      return key('voldown', count(t, DEFAULT_VOLUME_STEPS), 'Volume down');
    }

    if (/^(go home|home|home screen|main screen|go to home screen)$/.test(t)) return key('home', 1, 'Going home');
    if (/^(go back|back|previous|return)$/.test(t)) return key('back', 1, 'Going back');
    if (/^(menu|show menu|options)$/.test(t)) return key('menu', 1, 'Menu');
    if (/^(pause|resume|play|continue|stop|play pause|pause it|resume it|play it)$/.test(t)) return key('playpause', 1, 'Play / pause');
    if (/\b(fast forward|forward|skip ahead|skip)\b/.test(t)) return key('forward', 1, 'Fast forward');
    if (/\b(rewind|skip back)\b/.test(t)) return key('rewind', 1, 'Rewind');
    if (/\b(power off|turn (?:it )?off|switch (?:it )?off|sleep|standby|shut down)\b/.test(t)) return key('power', 1, 'Power');
    if (/^(select|ok|okay|enter|confirm|click|choose|press ok|yes)$/.test(t)) return key('ok', 1, 'OK');
    if (/^(clear|delete|backspace)$/.test(t)) return key('del', 1, 'Backspace');

    if ((m = /^(?:scroll |move |go |press )?(up|down|left|right)(?: (.+))?$/.exec(t))) {
      var steps = /^scroll/.test(t) ? DEFAULT_SCROLL_STEPS : 1;
      return key(m[1], m[2] ? count(m[2], steps) : steps, 'Moving ' + m[1]);
    }

    return { actions: [{ type: 'none' }], say: 'I did not understand "' + String(raw).trim() + '"' };
  }

  return { parse: parse, normalise: normalise, MAX_REPEAT: MAX_REPEAT };
});
