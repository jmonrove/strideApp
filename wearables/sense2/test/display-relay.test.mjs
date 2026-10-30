import test from 'node:test';
import assert from 'node:assert/strict';
import {inspectDisplayFrame,inspectFrame} from '../common/protocol.js';
import {createDisplayRelay} from '../companion/display-relay.js';
const now=1700000000000;
const frame=(sequence=1,id='watch-001')=>({kind:'hr_display',version:1,source:'sense2_exercise',sessionId:id,sequence,bpm:120,
 polledAtMs:now,pollAgeMs:0,acquisitionAgeMs:null,quality:'acquisition_unknown',status:'display_only',onWrist:true,displayOnly:true});
const drain=()=>new Promise(resolve=>setImmediate(resolve));
function setup(configured=true, fetchImpl){let time=now;const requests=[],acks=[],logs=[];
 const relay=createDisplayRelay({now:()=>time,setting:key=>configured?JSON.stringify({name:key==='endpoint'?'http://lab:8780/api/hr':'secret'}):null,
 fetch:async(url,options)=>{requests.push({url,...options});return fetchImpl?fetchImpl():{status:200};},ack:x=>acks.push(x),log:x=>logs.push(x)});
 return {relay,requests,acks,logs,advance:ms=>time+=ms};}
test('exercise poll has unknown acquisition age and cannot enter the guidance protocol',()=>{
 const f=frame();const result=inspectDisplayFrame(f,now+1000);assert.equal(result.ok,true);assert.equal(result.sample.pollAgeMs,1000);
 assert.equal(result.sample.acquisitionAgeMs,null);assert.equal(result.sample.displayOnly,true);assert.equal(inspectFrame(f,now).ok,false);
 assert.equal(inspectDisplayFrame({...f,bpm:120.6},now).sample.bpm,121);
 for(const changes of [{displayOnly:false},{acquisitionAgeMs:0},{quality:'fresh'},{source:'sense2'},{onWrist:false},{bpm:240.5},{polledAtMs:now+3000}])
  assert.equal(inspectDisplayFrame({...f,...changes},now).ok,false);
 assert.equal(inspectDisplayFrame(f,now+6000).ok,false);
});
test('only the display endpoint receives exercise HR, and logs omit biometric values',async()=>{
 const h=setup();h.relay.receive(frame());await drain();assert.equal(h.requests.length,1);
 assert.equal(h.requests[0].url,'http://lab:8780/api/hr-display');assert.equal(h.requests[0].headers.Authorization,'Bearer secret');
 const body=JSON.parse(h.requests[0].body);assert.equal(body.displayOnly,true);assert.equal(body.acquisitionAgeMs,null);
 assert.ok(h.logs.every(x=>!x.includes('120')));assert.equal(h.acks[0].kind,'display_ack');
});
test('display forwarding is opt-in; repeat polls and retired sessions cannot replay',async()=>{
 const unpaired=setup(false);unpaired.relay.receive(frame());await drain();assert.equal(unpaired.requests.length,0);assert.equal(unpaired.acks.length,1);
 const h=setup();h.relay.receive(frame());await drain();h.relay.receive(frame());await drain();assert.equal(h.requests.length,1);
 h.relay.receive(frame(1,'watch-002'));await drain();h.relay.receive(frame(2));await drain();assert.equal(h.requests.length,2);
});
test('an inactive notification clears the readout after an in-flight value, without an old backlog',async()=>{
 let resolve;const h=setup(true,()=>new Promise(r=>resolve=r));h.relay.receive(frame());h.relay.receive(frame(2));
 const inactive={...frame(3),bpm:null,status:'inactive'};h.relay.receive(inactive);resolve({status:200});await drain();
 assert.equal(h.requests.length,2);assert.equal(JSON.parse(h.requests[1].body).bpm,null);assert.equal(JSON.parse(h.requests[1].body).status,'inactive');
 resolve({status:200});await drain();
});
test('a delayed display queue expires without restarting a cached poll',async()=>{
 let resolve;const h=setup(true,()=>new Promise(r=>resolve=r));h.relay.receive(frame());h.relay.receive(frame(2));
 h.advance(6000);resolve({status:200});await drain();assert.equal(h.requests.length,1);
});
