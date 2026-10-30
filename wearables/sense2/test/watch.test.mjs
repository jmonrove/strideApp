import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const source=fs.readFileSync(new URL('../app/index.js',import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
function setup({activity=true,heart=true,bodyAvailable=true,modernNumber=true,brokenPeer=null}={}) {
 const ui={},sent=[],timers=[];let heartSensor,bodySensor,now=1_000_000;
 class FakeDate extends Date { constructor(...args){super(...(args.length?args:[now]));}static now(){return now;} }
 class HeartRateSensor {
  constructor(){this.events={};this.timestamp=null;this.heartRate=null;heartSensor=this;this.started=false;this.startCount=0;}
  addEventListener(name,handler){this.events[name]=handler;}
  start(){this.started=true;this.startCount++;}
  stop(){this.started=false;}
 }
 class BodyPresenceSensor {
  constructor(){this.events={};this.present=false;bodySensor=this;}
  addEventListener(name,handler){this.events[name]=handler;}
  start(){}
 }
 const me={appTimeoutEnabled:true,permissions:{granted:name=>name==='access_activity'?activity:heart}};
 const display={on:true,events:{},addEventListener(name,handler){this.events[name]=handler;}};
 const peer={OPEN:1,readyState:1,bufferedAmount:0,send:frame=>sent.push(frame),addEventListener(){}};
 if(brokenPeer==='state')Object.defineProperty(peer,'readyState',{get(){throw TypeError('Expected a function');}});
 if(brokenPeer==='bind')peer.addEventListener=()=>{throw TypeError('Expected a function');};
 if(brokenPeer==='send')peer.send=()=>{throw TypeError('Expected a function');};
 const context={document:{getElementById:id=>ui[id]??=({text:'',events:{},addEventListener(name,handler){this.events[name]=handler;}})},HeartRateSensor,
  BodyPresenceSensor:bodyAvailable?BodyPresenceSensor:undefined,me,display,
  messaging:{peerSocket:peer},
  setInterval(handler){timers.push(handler);},console:{log(){}},Date:FakeDate,Math,Number:modernNumber?Number:{},String};
 vm.runInNewContext(source,context);
 return {ui,sent,display,me,get hr(){return heartSensor},get body(){return bodySensor},
  tick(ms=1000){now+=ms;timers.forEach(fn=>fn());},
  read(value,timestamp){heartSensor.heartRate=value;heartSensor.timestamp=timestamp;heartSensor.events.reading();},
  wear(value){bodySensor.present=value;bodySensor.events.reading();}};
}
test('requested watch permissions include the body-presence permission',()=>{
 const manifest=JSON.parse(fs.readFileSync(new URL('../package.json',import.meta.url),'utf8'));
 assert.ok(manifest.fitbit.requestedPermissions.includes('access_activity'));
});
test('missing activity permission shows permission denied without starting sensors',()=>{
 const h=setup({activity:false});assert.equal(h.ui.state.text,'permission denied');assert.equal(h.hr,undefined);
});
test('missing wear detection does not start a heart-rate stream',()=>{
 const h=setup({bodyAvailable:false});assert.equal(h.hr,undefined);assert.equal(h.ui.state.text,'wear detection unavailable');
});
test('watch waits for confirmed wear and invalidates input when wear detection fails',()=>{
 const h=setup();assert.equal(h.hr.started,false);
 h.wear(true);assert.equal(h.hr.started,true);h.read(112,100);
 assert.equal(h.sent.at(-1).status,'ok');assert.equal(h.sent.at(-1).onWrist,true);
 const sequence=h.sent.at(-1).sequence;
 h.body.events.error();assert.equal(h.hr.started,false);assert.equal(h.sent.at(-1).status,'wear_detection_error');
 h.read(113,101);h.tick();assert.equal(h.sent.at(-1).sequence,sequence);assert.equal(h.sent.at(-1).bpm,null);
});
test('repeated on-wrist events preserve fresh BPM and do not restart acquisition',()=>{
 const h=setup();h.wear(true);h.read(112,100);
 assert.equal(h.ui.state.text,'ok');assert.equal(h.ui.bpm.text,'112');
 h.wear(true);h.wear(true);
 assert.equal(h.hr.startCount,1);assert.equal(h.ui.state.text,'ok');assert.equal(h.ui.bpm.text,'112');
});
test('wear flapping invalidates forwarding immediately without starving acquisition',()=>{
 const h=setup();h.wear(true);h.read(112,100);h.wear(false);
 assert.equal(h.hr.started,true);assert.equal(h.ui.bpm.text,'--');
 assert.equal(h.sent.at(-1).status,'off_wrist');assert.equal(h.sent.at(-1).bpm,null);
 h.read(115,101);h.wear(true);h.read(115,101);h.tick();
 assert.equal(h.ui.state.text,'waiting');assert.equal(h.sent.at(-1).bpm,null);
 assert.equal(h.hr.startCount,1);
 h.read(116,102);assert.equal(h.ui.bpm.text,'116');assert.equal(h.sent.at(-1).sequence,2);
 assert.match(h.ui.wear.text,/flips 3 \/ starts 1/);
});
test('null/raw events are distinguished from valid heart-rate samples',()=>{
 const h=setup();h.wear(true);h.read(null,1);
 assert.equal(h.ui.state.text,'invalid reading');assert.match(h.ui.raw.text,/raw HR null/);
 assert.match(h.ui.count.text,/1 events \/ 1 unique \/ 0 valid/);
 h.read(undefined,2);h.read(0,3);h.tick();
 assert.equal(h.sent.at(-1).bpm,null);assert.equal(h.sent.at(-1).sequence,0);
 h.read(120,4);assert.equal(h.ui.bpm.text,'120');assert.match(h.ui.count.text,/4 events \/ 4 unique \/ 1 valid/);
});
test('absent, duplicate, and backwards sensor timestamps never freshen a sample',()=>{
 const h=setup();h.wear(true);h.read(120,undefined);
 assert.equal(h.ui.state.text,'invalid timestamp');assert.equal(h.sent.at(-1).sequence,0);
 h.read(120,100);h.tick(4000);h.read(130,100);h.read(135,99);h.tick(2000);
 assert.equal(h.ui.bpm.text,'--');assert.equal(h.ui.state.text,'stale');assert.equal(h.sent.at(-1).sequence,1);
 assert.equal(h.ui.last.text,'last 120 BPM / 6s ago');
});
test('display changes and heartbeat ticks never restart sensing or revive stale data',()=>{
 const h=setup();h.wear(true);h.read(120,100);
 h.display.on=false;h.display.events.change();h.tick(10000);
 assert.equal(h.ui.state.text,'stale');assert.equal(h.sent.at(-1).sequence,1);
 h.display.on=true;h.display.events.change();
 assert.equal(h.hr.startCount,1);assert.equal(h.ui.bpm.text,'--');
 assert.match(h.ui.runtime.text,/wakes 2 \/ ticks 1 \/ 10s/);
});
test('pause stops the diagnostic sensor, resume requires a new reading, and inactivity timeout is requested off',()=>{
 const h=setup();assert.equal(h.me.appTimeoutEnabled,false);
 h.wear(true);h.read(120,100);h.ui.toggle.events.click();
 assert.equal(h.hr.started,false);assert.equal(h.ui.state.text,'paused');assert.equal(h.me.appTimeoutEnabled,true);
 h.read(120,101);h.ui.toggle.events.click();
 assert.equal(h.hr.startCount,2);assert.equal(h.me.appTimeoutEnabled,false);assert.equal(h.ui.state.text,'waiting');
 h.read(120,101);assert.equal(h.ui.state.text,'waiting');
 h.read(120,102);assert.equal(h.ui.bpm.text,'120');
});

test('device numeric checks work when Number.isFinite is unavailable',()=>{
 const h=setup({modernNumber:false});h.wear(true);h.read(120,100);
 assert.equal(h.ui.bpm.text,'120');assert.equal(h.ui.state.text,'ok');
 h.tick();assert.match(h.ui.runtime.text,/ticks 1/);assert.match(h.ui.count.text,/1 valid/);
 for(const value of [null,undefined,NaN,Infinity,-Infinity,'120',0]){
  h.read(value,++h.hr.timestamp);assert.equal(h.ui.state.text,'invalid reading');
 }
});

for(const brokenPeer of ['state','bind','send']) {
 test(`local acquisition/rendering survive a native peer ${brokenPeer} exception`,()=>{
  const h=setup({brokenPeer});h.wear(true);h.read(120,100);h.tick();
  assert.equal(h.ui.bpm.text,'120');assert.equal(h.ui.state.text,'ok');
  assert.equal(h.ui.link.text,'Phone API unavailable');
  assert.match(h.ui.count.text,/1 events \/ 1 unique \/ 1 valid/);
  assert.match(h.ui.runtime.text,/ticks 1/);
  h.tick(6000);assert.equal(h.ui.state.text,'stale');
 });
}
