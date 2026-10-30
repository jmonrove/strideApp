import * as messaging from 'messaging';
import { settingsStorage } from 'settings';
import { inspectFrame, parseSetting, validEndpoint } from '../common/protocol';

let lastSession = '';
let lastSequence = 0;
let inFlight = false;
let latest = null;
let pending = null;
let lastStatus = '';
const retiredSessions = [];
function tellWatch(data) {
  if (messaging.peerSocket.readyState === messaging.peerSocket.OPEN && messaging.peerSocket.bufferedAmount < 1024) {
    try { messaging.peerSocket.send(data); } catch (_) {}
  }
}
async function forward() {
  if (inFlight || !pending) return;
  const endpoint = parseSetting(settingsStorage.getItem('endpoint'));
  const token = parseSetting(settingsStorage.getItem('token'));
  if (!validEndpoint(endpoint) || !token) return; // Console-only probe until paired.
  const frame = pending;
  pending = null;
  const checked = inspectFrame(frame, Date.now());
  if (!checked.ok) { console.log('HR_PROBE not forwarded: ' + checked.reason); return; }
  inFlight = true;
  let completed = false;
  // Do not begin overlapping fetches after a stalled request. Reconnect the app if one stalls.
  const timeout = setTimeout(() => {
    if (!completed) { console.log('HR_PROBE receiver request stalled; no fresh samples forwarded'); tellWatch({ kind: 'receiver', state: 'stalled' }); }
  }, 5000);
  try {
    const response = await fetch(endpoint, { method: 'POST', headers: {
      'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token,
    }, body: JSON.stringify(checked.sample) });
    console.log('HR_PROBE receiver HTTP ' + response.status);
  } catch (error) { console.log('HR_PROBE receiver unavailable: ' + error.message); }
  finally { completed = true; clearTimeout(timeout); inFlight = false; }
  // Never replay an old backlog. Only the latest pending sensor sample is considered.
  if (pending) forward();
}
messaging.peerSocket.addEventListener('message', event => {
  const frame = event.data;
  const checked = inspectFrame(frame, Date.now());
  if (!checked.ok) {
    pending = null;
    latest = null;
    const state = checked.reason;
    if (state !== lastStatus) console.log('HR_PROBE status: ' + state);
    lastStatus = state;
    return;
  }
  if (retiredSessions.indexOf(frame.sessionId) >= 0) return;
  if (frame.sessionId !== lastSession) {
    if (lastSession) retiredSessions.push(lastSession);
    if (retiredSessions.length > 16) retiredSessions.shift();
    lastSession = frame.sessionId;
    lastSequence = 0;
  }
  if (frame.sequence <= lastSequence) return;
  lastSequence = frame.sequence;
  latest = frame;
  pending = frame;
  lastStatus = 'ok';
  // Fitbit CLI displays this companion-side line, proving the reading reached the phone.
  console.log('HR_PROBE ' + JSON.stringify({ bpm: frame.bpm, sequence: frame.sequence, ageMs: checked.sample.ageMs, source: 'sense2' }));
  tellWatch({ kind: 'ack', sequence: frame.sequence });
  forward();
});
settingsStorage.addEventListener('change', () => {
  if (latest) pending = latest;
  forward();
});
console.log('HR_PROBE companion ready; no upload destination configured by default');
