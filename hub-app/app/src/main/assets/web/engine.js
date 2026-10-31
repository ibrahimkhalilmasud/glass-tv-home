/* Camera + hand tracking for the phone page and the TV-camera page.
 *
 *   TvEngine.start({ video, mirror, onStatus(text), onFrame(poseOrNull), onAction({key, say}) }) -> Promise<{stop()}>
 *
 * Needs a secure page (https, or 127.0.0.1) for the camera. The hand-tracking library and model are served by the
 * TV Hub app itself (/mp/...), so nothing is downloaded from the internet. Needs classify.js loaded first.
 */
(function (root) {
  'use strict';

  var MIN_FRAME_MS = 50;   // about 20 detections per second is plenty for hand gestures
  var CAMERA_WIDTH = 640;
  var CAMERA_HEIGHT = 480;

  function loadLandmarker(vision, fileset) {
    var base = { modelAssetPath: '/mp/hand_landmarker.task' };
    var options = { runningMode: 'VIDEO', numHands: 1 };
    // The GPU path is faster; some phones and WebViews cannot do it, so fall back to the CPU.
    return vision.HandLandmarker.createFromOptions(fileset, Object.assign({ baseOptions: Object.assign({ delegate: 'GPU' }, base) }, options))
      .catch(function () {
        return vision.HandLandmarker.createFromOptions(fileset, Object.assign({ baseOptions: base }, options));
      });
  }

  function openCamera() {
    var md = navigator.mediaDevices;
    return md.getUserMedia({ video: { facingMode: 'user', width: { ideal: CAMERA_WIDTH }, height: { ideal: CAMERA_HEIGHT } }, audio: false })
      .catch(function (e) {
        if (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) throw e;
        return md.getUserMedia({ video: true, audio: false });   // an external camera may not report a facing mode
      });
  }

  function start(opts) {
    var video = opts.video, say = opts.onStatus || function () {};
    if (!root.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      return Promise.reject(Object.assign(new Error('insecure'), { name: 'InsecureContext' }));
    }
    say('Loading hand tracking…');
    var landmarker, stream;
    return import('/mp/vision_bundle.mjs')
      .then(function (vision) {
        return vision.FilesetResolver.forVisionTasks('/mp/wasm').then(function (fs) { return loadLandmarker(vision, fs); });
      })
      .then(function (lm) { landmarker = lm; say('Starting the camera…'); return openCamera(); })
      .then(function (s) { stream = s; video.srcObject = s; return video.play(); })
      .then(function () {
        var tracker = root.TvClassify.createTracker({ mirror: opts.mirror !== false });
        var running = true, lastTime = -1, lastRun = 0;
        function frame() {
          if (!running) return;
          var now = performance.now();
          if (video.readyState >= 2 && video.currentTime !== lastTime && now - lastRun >= MIN_FRAME_MS) {
            lastTime = video.currentTime; lastRun = now;
            var res = landmarker.detectForVideo(video, now);
            var lm = res && res.landmarks && res.landmarks[0] ? res.landmarks[0] : null;
            var action = tracker.update(lm, now);
            if (opts.onFrame) opts.onFrame(lm ? root.TvClassify.pose(lm) : null);
            if (action && opts.onAction) opts.onAction(action);
          }
          requestAnimationFrame(frame);
        }
        frame();
        say('Ready. Show your hand.');
        return {
          stop: function () {
            running = false;
            stream.getTracks().forEach(function (t) { t.stop(); });
            video.srcObject = null;
            landmarker.close();
          }
        };
      })
      .catch(function (e) {
        if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
        throw e;
      });
  }

  root.TvEngine = { start: start };
})(window);
