import {inspectDisplayFrame, parseSetting, validEndpoint} from '../common/protocol.js';
export function createDisplayRelay(options) {
  let session = '', sequence = 0, pending = null, inFlight = false;
  const retired = [];
  async function forward() {
    if (inFlight || !pending) return;
    const endpoint = parseSetting(options.setting('endpoint')), token = parseSetting(options.setting('token'));
    if (!validEndpoint(endpoint) || !token) return;
    const frame = pending; pending = null;
    const checked = inspectDisplayFrame(frame, options.now());
    if (!checked.ok) return;
    inFlight = true;
    try {
      const response = await options.fetch(endpoint.replace(/\/api\/hr$/, '/api/hr-display'), {
        method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer ' + token},body:JSON.stringify(checked.sample)});
      options.log('HR_DISPLAY receiver HTTP ' + response.status);
    } catch (_) { options.log('HR_DISPLAY receiver unavailable'); }
    finally { inFlight = false; }
    if (pending) forward();
  }
  function receive(frame) {
    const checked = inspectDisplayFrame(frame, options.now());
    if (!checked.ok) { pending = null; return; }
    if (retired.indexOf(frame.sessionId) >= 0) return;
    if (session !== frame.sessionId) {
      if (retired.length >= 64) return;
      if (session) retired.push(session);
      session = frame.sessionId; sequence = 0;
    }
    if (frame.sequence <= sequence) return;
    sequence = frame.sequence; pending = frame;
    options.ack({kind:'display_ack',sequence});
    options.log('HR_DISPLAY received #' + sequence + ' ' + frame.status);
    forward();
  }
  return {receive,forward};
}
