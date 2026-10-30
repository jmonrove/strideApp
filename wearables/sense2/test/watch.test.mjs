import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const source=fs.readFileSync(new URL('../app/index.js',import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
function setup({activity=true,heart=true,bodyAvailable=true}={}) {
 const ui={},sent=[];let heartSensor,bodySensor;
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
 const context={document:{getElementById:id=>ui[id]??=( {text:'',addEventListener(){}} )},HeartRateSensor,
  BodyPresenceSensor:bodyAvailable?BodyPresenceSensor:undefined,
  me:{permissions:{granted:name=>name==='access_activity'?activity:heart}},
  messaging:{peerSocket:{OPEN:1,readyState:1,bufferedAmount:0,send:frame=>sent.push(frame),addEventListener(){}}},
  setInterval(){},console:{log(){}},Date,Math,Number,String};
 vm.runInNewContext(source,context);
 return {ui,sent,get hr(){return heartSensor},get body(){return bodySensor}};
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
 h.body.present=true;h.body.events.reading();assert.equal(h.hr.started,true);
 h.hr.timestamp=100;h.hr.heartRate=112;h.hr.events.reading();
 assert.equal(h.sent.at(-1).status,'ok');assert.equal(h.sent.at(-1).onWrist,true);
 const sequence=h.sent.at(-1).sequence;
 h.body.events.error();assert.equal(h.hr.started,false);assert.equal(h.sent.at(-1).status,'wear_detection_error');
 h.hr.timestamp=101;h.hr.events.reading();assert.equal(h.sent.at(-1).sequence,sequence);
});
test('repeated on-wrist events preserve fresh BPM and do not restart acquisition',()=>{
 const h=setup();h.body.present=true;h.body.events.reading();
 h.hr.timestamp=100;h.hr.heartRate=112;h.hr.events.reading();
 assert.equal(h.ui.state.text,'ok');assert.equal(h.ui.bpm.text,'112');
 h.body.events.reading();h.body.events.reading();
 assert.equal(h.hr.startCount,1);assert.equal(h.ui.state.text,'ok');assert.equal(h.ui.bpm.text,'112');
 h.body.present=false;h.body.events.reading();assert.equal(h.hr.started,false);
 h.body.present=true;h.body.events.reading();assert.equal(h.hr.startCount,2);
});
