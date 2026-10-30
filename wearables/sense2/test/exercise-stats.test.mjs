import test from 'node:test';
import assert from 'node:assert/strict';
import {createStatsDiagnostic} from '../app/exercise-stats.js';
test('repeated exercise HR polls count separately from value changes and acquisition timestamps',()=>{
 const d=createStatsDiagnostic(1000);
 for(let i=0;i<6;i++)d.observe({activeTime:i*1000,heartRate:{current:100}},1000+i*1000);
 assert.deepEqual(d.snapshot(7000),{polls:6,validPolls:6,changes:0,timeAdvances:5,errors:0,maxSameSec:6});
 assert.equal(Object.hasOwn(d.snapshot(7000),'bpm'),false);
});
test('value changes are comparison counters only, with startup waiting included',()=>{
 const d=createStatsDiagnostic(1000);d.observe({activeTime:4000,heartRate:{current:100}},5000);
 d.observe({activeTime:6000,heartRate:{current:101}},7000);
 assert.equal(d.snapshot(8000).changes,1);assert.equal(d.snapshot(8000).maxSameSec,4);
});
test('unavailable and throwing stats remain isolated from valid HR input',()=>{
 const d=createStatsDiagnostic(1000);
 d.observe(undefined,2000);d.observe({get heartRate(){throw TypeError('Unavailable');}},3000);d.failed(4000);
 for(const bpm of [null,undefined,NaN,Infinity,'100',0,300])d.observe({heartRate:{current:bpm}},5000);
 assert.equal(d.snapshot(5000).errors,10);assert.equal(d.snapshot(5000).validPolls,0);
});
