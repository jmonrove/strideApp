import test from 'node:test';
import assert from 'node:assert/strict';
import {createExerciseTest} from '../app/exercise-test.js';
function setup({permission=true,existing='stopped',unsupported=false,startFails=false,startNoChange=false,stopFails=false,stopUnconfirmed=false}={}) {
 let now=100000,loads=0,starts=0,stops=0;
 const api={state:existing,start(type,options){starts++;api.type=type;api.options=options;if(startFails)throw TypeError('Native start error');if(!startNoChange)api.state='started';},
  stop(){stops++;if(stopFails)throw Error('Native stop error');if(!stopUnconfirmed)api.state='stopped';}};
 const controller=createExerciseTest({now:()=>now,permission:()=>permission,load(){loads++;if(unsupported)throw Error('Module unavailable');return api;}});
 const input={onWrist:true,paused:false};
 return {controller,api,input,get loads(){return loads;},get starts(){return starts;},get stops(){return stops;},
  advance(ms=1000){now+=ms;return controller.tick(input);},
  start(){controller.arm(input);return controller.confirm(input);}};
}
test('creation, snapshots and idle ticks do not load native module or record an activity',()=>{
 const h=setup();h.controller.snapshot();h.advance();assert.equal(h.loads,0);assert.equal(h.starts,0);
 h.controller.confirm(h.input);assert.equal(h.starts,0);
});
test('start needs a second explicit confirmation and expires without recording',()=>{
 const h=setup();assert.equal(h.controller.arm(h.input).phase,'armed');assert.equal(h.loads,0);
 h.advance(10000);assert.equal(h.controller.snapshot().phase,'idle');
 h.controller.confirm(h.input);assert.equal(h.starts,0);
});
test('missing wear or HR pause prevents both arming and confirmation',()=>{
 for(const input of [{onWrist:false,paused:false},{onWrist:null,paused:false},{onWrist:true,paused:true}]){
  const h=setup();assert.equal(h.controller.arm(input).phase,'blocked');assert.equal(h.loads,0);
  h.controller.arm(h.input);h.controller.confirm(input);assert.equal(h.starts,0);
 }
});
test('exercise permission is checked before native module loading',()=>{
 const h=setup({permission:false});assert.equal(h.start().phase,'blocked');assert.equal(h.loads,0);assert.equal(h.starts,0);
});
test('unsupported native exercise API is isolated from passive sensing',()=>{
 const h=setup({unsupported:true});assert.equal(h.start().phase,'unsupported');assert.equal(h.starts,0);
 h.advance();assert.equal(h.controller.snapshot().owned,false);
});
test('an existing started or paused exercise is never changed or stopped',()=>{
 for(const existing of ['started','paused']){
  const h=setup({existing});assert.equal(h.start().phase,'blocked');
  h.controller.stop();h.advance(65000);assert.equal(h.starts,0);assert.equal(h.stops,0);assert.equal(h.api.state,existing);
 }
});
test('unrecognized native states are unsupported, not mislabeled as active exercises',()=>{
 for(const existing of ['unknown',null,0,{},false]){
  const h=setup({existing});const result=h.start();assert.equal(result.phase,'unsupported');
  assert.match(result.message,/Exercise state:/);assert.equal(h.starts,0);assert.equal(h.stops,0);
 }
 const h=setup();delete h.api.state;assert.match(h.start().message,/undefined/);assert.equal(h.starts,0);
});
test('read-only state inspection never starts, stops, or takes ownership of an exercise',()=>{
 for(const existing of ['stopped','started','paused',null,0,'unexpected']){
  const h=setup({existing});const result=h.controller.inspect();
  assert.equal(result.valid,['stopped','started','paused'].includes(existing));
  assert.equal(h.starts,0);assert.equal(h.stops,0);assert.equal(h.controller.snapshot().owned,false);
  assert.equal(h.controller.snapshot().phase,'idle');
 }
});
test('confirmed start records a treadmill test without GPS or autopause',()=>{
 const h=setup();assert.equal(h.start().phase,'active');assert.equal(h.starts,1);
 assert.equal(h.api.type,'treadmill');assert.deepEqual(h.api.options,{gps:false,autopause:false});
 assert.equal(h.controller.snapshot().remainingSec,60);
});
test('repeated confirmation while active cannot start a second activity',()=>{
 const h=setup();h.start();h.controller.confirm(h.input);
 // A stray confirmation must not abandon ownership or reset a recording.
 assert.equal(h.starts,1);assert.equal(h.controller.snapshot().owned,true);assert.equal(h.controller.snapshot().phase,'active');
 h.advance(60000);assert.equal(h.stops,1);
});
test('test stops at sixty seconds, including startup and final sampling gaps',()=>{
 const h=setup();h.start();h.advance(1000);h.controller.reading();h.advance(2000);h.controller.reading();
 h.advance(57000);const result=h.controller.snapshot();
 assert.equal(h.stops,1);assert.equal(result.phase,'done');assert.equal(result.owned,false);assert.equal(result.samples,2);assert.equal(result.maxGapSec,57);
 h.advance(60000);assert.equal(h.stops,1);assert.equal(h.starts,1);
});
test('a delayed callback stops instead of extending the recording by another minute',()=>{
 const h=setup();h.start();h.advance(90000);assert.equal(h.stops,1);assert.equal(h.api.state,'stopped');
});
test('clock rollback terminates the owned test',()=>{
 const h=setup();h.start();h.advance(-1);assert.equal(h.stops,1);assert.equal(h.controller.snapshot().phase,'done');
});
test('wear loss and HR pause stop only the owned test',()=>{
 for(const field of ['onWrist','paused']){
  const h=setup();h.start();h.input[field]=field==='paused';h.advance();assert.equal(h.stops,1);
 }
});
test('explicit stop and unload cleanup are idempotent for completed tests',()=>{
 const h=setup();h.start();h.controller.stop('Probe closed');h.controller.stop('Probe closed');assert.equal(h.stops,1);
});
test('native start failure does not retry or leak ownership when state remained stopped',()=>{
 const h=setup({startFails:true});assert.equal(h.start().phase,'error');assert.equal(h.controller.snapshot().owned,false);
 assert.deepEqual(h.controller.snapshot().failure,{stage:'native start',name:'TypeError',detail:'Native start error',state:'stopped'});
 h.advance(60000);assert.equal(h.starts,1);assert.equal(h.stops,0);
});
test('a returned but unconfirmed start is distinguished from a native exception',()=>{
 const h=setup({startNoChange:true});assert.equal(h.start().phase,'error');
 assert.deepEqual(h.controller.snapshot().failure,{stage:'state after return',name:'Unconfirmed',detail:'start returned; state=stopped',state:'stopped'});
 h.advance();assert.equal(h.starts,1);assert.equal(h.stops,0);
 h.api.state='started';h.advance();assert.equal(h.controller.snapshot().failure.state,'started');
 assert.equal(h.starts,1);assert.equal(h.stops,0);
});
test('failed or unconfirmed stop remains visible and blocks a replacement recording',()=>{
 for(const settings of [{stopFails:true},{stopUnconfirmed:true}]){
  const h=setup(settings);h.start();h.advance(60000);
  assert.equal(h.controller.snapshot().phase,'error');assert.equal(h.controller.snapshot().owned,true);
  h.controller.arm(h.input);h.controller.confirm(h.input);assert.equal(h.starts,1);
  h.advance(60000);assert.equal(h.stops,1);
 }
});
