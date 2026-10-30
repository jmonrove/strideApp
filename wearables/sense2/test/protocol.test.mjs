import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectFrame, parseSetting, validEndpoint } from '../common/protocol.js';
const now = 1700000000000;
const frame = () => ({kind:'hr',version:1,source:'sense2',sessionId:'session-1',sequence:1,bpm:110,observedAtMs:now-200,ageMs:200,status:'ok',onWrist:true});
test('preserves age across a delayed phone delivery', () => {
 const f=frame(); f.ageMs=0;
 assert.equal(inspectFrame(f,now+1000).sample.ageMs,1200);
});
test('rejects stale transport data even when reported age is zero', () => {
 const f=frame(); f.ageMs=0;
 assert.equal(inspectFrame(f,now+6000).reason,'stale');
});
test('rejects off-wrist and paused readings even when a BPM remains cached', () => {
 for(const status of ['off_wrist','paused','stale']) {
  const f=frame();f.status=status;
  assert.equal(inspectFrame(f,now).ok,false);
 }
 const f=frame();f.onWrist=false;assert.equal(inspectFrame(f,now).ok,false);
});
test('unknown or malformed wear state cannot qualify a heart-rate sample', () => {
 for (const onWrist of [null, undefined, 1, 'true']) {
  const f=frame();f.onWrist=onWrist;
  assert.equal(inspectFrame(f,now).reason,'unknown_wear_state');
 }
});
test('rejects null, non-finite, invalid HR and malformed sequence', () => {
 for(const bpm of [null,NaN,Infinity,-1,0,301,'110']) {
  const f=frame();f.bpm=bpm;assert.equal(inspectFrame(f,now).ok,false);
 }
 const f=frame(); f.sequence=1.5;assert.equal(inspectFrame(f,now).ok,false);
});
test('watch clock too far ahead cannot falsely label a reading fresh', () => {
 const f=frame();f.observedAtMs=now+3000;
 assert.equal(inspectFrame(f,now).reason,'clock_skew');
});
test('endpoint is opt-in and settings parsing accepts Fitbit TextInput format', () => {
 assert.equal(parseSetting(null),''); assert.equal(parseSetting('bad'),'');
 assert.equal(parseSetting('{"name":"http://192.168.1.4:8780/api/hr"}'),'http://192.168.1.4:8780/api/hr');
 for(const url of ['', 'file:///tmp/secret', 'http://host/api/hr?token=abc','http://host/bad']) assert.equal(validEndpoint(url),false);
 assert.equal(validEndpoint('http://192.168.1.4:8780/api/hr'),true);
});
