import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import { inspectFrame, parseSetting, validEndpoint } from '../common/protocol.js';
const source=fs.readFileSync(new URL('../companion/index.js',import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
function setup({ configured=true, fetchImpl }={}) {
 const callbacks={};const sent=[];const requests=[];const logs=[];let now=1700000000000;
 const stored={endpoint:JSON.stringify({name:'http://lab:8780/api/hr'}),token:JSON.stringify({name:'test-token'})};
 const peerSocket={ OPEN:1,readyState:1,bufferedAmount:0,send:v=>sent.push(v),addEventListener:(k,fn)=>callbacks[k]=fn };
 const context={messaging:{peerSocket},settingsStorage:{getItem:k=>configured?stored[k]:null,addEventListener:()=>{}},inspectFrame,parseSetting,validEndpoint,Number,JSON,Date:{now:()=>now},console:{log:v=>logs.push(v)},setTimeout:()=>1,clearTimeout:()=>{},fetch:async(url,opts)=>{requests.push({url,...opts});return fetchImpl?fetchImpl():{status:200};}};
 vm.runInNewContext(source,context);
 const frame=(sequence,sessionId='watch-A')=>({kind:'hr',version:1,source:'sense2',sessionId,sequence,bpm:110,observedAtMs:now,ageMs:0,status:'ok',onWrist:true});
 return {callbacks,sent,requests,logs,frame,advance:ms=>now+=ms};
}
const drain=()=>new Promise(resolve=>setImmediate(resolve));
test('companion proves receipt but uploads nothing until endpoint and token are set',async()=>{
 const h=setup({configured:false});h.callbacks.message({data:h.frame(1)});await drain();
 assert.equal(h.sent.length,1);assert.equal(h.requests.length,0);assert.ok(h.logs.some(v=>v.startsWith('HR_PROBE {')));
});
test('same reading in multiple heartbeats is sent only once and retired sessions cannot replay',async()=>{
 const h=setup();h.callbacks.message({data:h.frame(1)});await drain();
 h.callbacks.message({data:h.frame(1)});await drain();assert.equal(h.requests.length,1);
 h.callbacks.message({data:h.frame(1,'watch-B')});await drain();
 h.callbacks.message({data:h.frame(2,'watch-A')});await drain();assert.equal(h.requests.length,2);
 assert.equal(h.requests[0].headers.Authorization,'Bearer test-token');
 assert.equal(JSON.parse(h.requests[0].body).sequence,1);
});
test('off-wrist notification discards queued sensor data during an in-flight request',async()=>{
 let resolveFetch;const h=setup({fetchImpl:()=>new Promise(resolve=>resolveFetch=resolve)});
 h.callbacks.message({data:h.frame(1)});
 h.callbacks.message({data:h.frame(2)});
 const off=h.frame(2);off.status='off_wrist';off.onWrist=false;h.callbacks.message({data:off});
 resolveFetch({status:200});await drain();assert.equal(h.requests.length,1);
});
test('a slow request cannot cause a burst of outdated samples on recovery',async()=>{
 let resolveFetch;const h=setup({fetchImpl:()=>new Promise(resolve=>resolveFetch=resolve)});
 h.callbacks.message({data:h.frame(1)});h.callbacks.message({data:h.frame(2)});
 h.advance(7000);resolveFetch({status:200});await drain();assert.equal(h.requests.length,1);
 assert.ok(h.logs.some(v=>v.includes('not forwarded: stale')));
});
