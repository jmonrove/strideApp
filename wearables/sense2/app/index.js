import * as document from 'document';
import { HeartRateSensor } from 'heart-rate';
import { BodyPresenceSensor } from 'body-presence';
import { me } from 'appbit';
import * as messaging from 'messaging';

const sessionId = 'sense2-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
let sequence = 0;
let sensorTimestamp = null;
let lastObservedAt = 0;
let latestBpm = null;
let onWrist = null;
let paused = false;
let status = 'waiting';
let hrm = null;
let body = null;
let lastSentSequence = 0;
const ui = {};
['bpm', 'state', 'link', 'count', 'clock', 'toggle', 'toggle-label'].forEach(id => ui[id] = document.getElementById(id));
function render() {
  const now = new Date();
  ui.clock.text = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2);
  ui.bpm.text = status === 'ok' ? String(latestBpm) : '--';
  ui.state.text = status.replace(/_/g, ' ');
  ui.link.text = messaging.peerSocket.readyState === messaging.peerSocket.OPEN ? 'Phone connected' : 'Phone disconnected';
  ui.count.text = sequence + ' sensor samples';
  ui['toggle-label'].text = paused ? 'Resume stream' : 'Pause stream';
}
function frame() {
  return { kind: 'hr', version: 1, source: 'sense2', sessionId, sequence,
    bpm: latestBpm, sensorTimestampMs: sensorTimestamp, observedAtMs: lastObservedAt,
    sentAtMs: Date.now(), ageMs: lastObservedAt ? Date.now() - lastObservedAt : 0,
    status, onWrist };
}
function publish() {
  render();
  if (messaging.peerSocket.readyState !== messaging.peerSocket.OPEN || messaging.peerSocket.bufferedAmount > 1024) return;
  // Status messages are useful during a drop-out, but never increment the sensor sequence.
  try { messaging.peerSocket.send(frame()); lastSentSequence = sequence; }
  catch (error) { console.log('HR_PROBE watch send failed: ' + error.message); }
}
function startSensor() {
  if (paused) return;
  if (onWrist !== true) { status = onWrist === false ? 'off_wrist' : 'waiting_for_wear'; publish(); return; }
  if (hrm) { status = 'waiting'; hrm.start(); }
}
if (!me.permissions.granted('access_heart_rate') || !me.permissions.granted('access_activity')) {
  status = 'permission_denied';
} else if (!HeartRateSensor) {
  status = 'unsupported';
} else if (!BodyPresenceSensor) {
  // Unknown wear state must not be used as trustworthy input for workout guidance.
  status = 'wear_detection_unavailable';
} else {
  hrm = new HeartRateSensor({ frequency: 1 });
  hrm.addEventListener('reading', () => {
    if (paused || onWrist !== true) return;
    // Repeated cached timestamps must not masquerade as fresh sensor readings.
    if (hrm.timestamp === null || hrm.timestamp === sensorTimestamp) return;
    sensorTimestamp = hrm.timestamp;
    latestBpm = hrm.heartRate;
    lastObservedAt = Date.now();
    sequence++;
    status = Number.isFinite(latestBpm) && latestBpm > 0 ? 'ok' : 'invalid_reading';
    publish();
  });
  hrm.addEventListener('error', event => { status = 'sensor_error'; console.log('HR_PROBE sensor error: ' + event.error); publish(); });
  if (BodyPresenceSensor) {
    body = new BodyPresenceSensor();
    body.addEventListener('reading', () => {
      const wasOnWrist = onWrist;
      onWrist = body.present;
      if (!onWrist) { hrm.stop(); status = 'off_wrist'; publish(); }
      // Presence events may repeat while worn. Restarting on every event clears
      // a good BPM to "waiting" and can repeatedly restart HR acquisition.
      else if (wasOnWrist !== true) startSensor();
    });
    body.addEventListener('error', () => {
      onWrist = false;
      hrm.stop();
      status = 'wear_detection_error';
      publish();
    });
    body.start();
  }
  startSensor();
}
function toggle() {
  if (!hrm) return;
  paused = !paused;
  if (paused) { hrm.stop(); status = 'paused'; }
  else startSensor();
  publish();
}
ui.toggle.addEventListener('click', toggle);
ui['toggle-label'].addEventListener('click', toggle);
messaging.peerSocket.addEventListener('open', publish);
messaging.peerSocket.addEventListener('close', render);
messaging.peerSocket.addEventListener('message', event => {
  if (event.data && event.data.kind === 'ack') ui.link.text = 'Phone received #' + event.data.sequence;
});
setInterval(() => {
  if (!paused && status === 'ok' && Date.now() - lastObservedAt > 5000) status = 'stale';
  // Heartbeats report connection/status; the companion forwards each sensor sequence only once.
  if (sequence === lastSentSequence || status !== 'ok') publish();
}, 1000);
render();
