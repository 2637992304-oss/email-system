import http from 'k6/http';
import {check,sleep} from 'k6';
import {Trend,Counter,Rate} from 'k6/metrics';
import execution from 'k6/execution';
const submitMs=new Trend('submit_ms',true),e2eMs=new Trend('delivery_e2e_ms',true),deliveryFailures=new Counter('delivery_failures'),expected429=new Counter('expected_429'),unexpected=new Rate('unexpected_errors'),success=new Counter('successful_requests'),acceptedSubmissions=new Counter('accepted_submissions');
const base=__ENV.BASE_URL||'http://frontend';
export const options={scenarios:{load:{executor:'constant-arrival-rate',rate:Number(__ENV.RATE||100),timeUnit:'1s',duration:__ENV.DURATION||'10m',gracefulStop:'180s',preAllocatedVUs:150,maxVUs:500}},summaryTrendStats:['avg','med','p(95)','p(99)','max'],thresholds:{unexpected_errors:['rate<0.01'],http_req_duration:['p(95)<500'],delivery_failures:['count==0'],dropped_iterations:['count==0']}};
export function setup(){
 deliveryFailures.add(0);expected429.add(0);
 const login=(n)=>{const r=http.post(base+'/api/auth/login',JSON.stringify({email:`bench${n}@example.test`,password:__ENV.HC_BENCH_PASSWORD||'LocalBench42!'}),{headers:{'Content-Type':'application/json'}});if(r.status!==200)throw new Error('Benchmark login failed');return r.json('data.token');};
 const sender=login(0),reader=login(1);let attachment=null;
 if(__ENV.MODE==='attachment') {
   const staged=http.post(base+'/api/attachment/upload',{file:http.file('fixture42 '.repeat(8192),'fixture.txt','text/plain')},{headers:{Authorization:'Bearer '+sender}});
   if(staged.status!==200)throw new Error('Attachment fixture upload failed');attachment=staged.json('data.attachmentId');
 }
 const headers={Authorization:'Bearer '+sender,'Content-Type':'application/json','Idempotency-Key':`setup-${__ENV.RUN||'local'}`};
 const result=http.post(base+'/api/mail/send',JSON.stringify({to:['bench1@example.test'],subject:'status fixture',content:'fixed',attachmentIds:attachment?[attachment]:[]}),{headers});
 if(result.status!==202)throw new Error('Status fixture submission failed');
 const statusMail=result.json('data.mailId');console.log('ACCEPTED '+statusMail+' KEY setup-'+(__ENV.RUN||'local'));
 if(attachment) {
   let delivered=false;
   for(let i=0;i<120;i++){sleep(1);const state=http.get(base+`/api/mail/${statusMail}/status`,{headers:{Authorization:'Bearer '+sender}});if(state.status===200&&state.json('data.status')==='DELIVERED'){delivered=true;break;}}
   if(!delivered)throw new Error('Attachment fixture delivery failed');
 }
 return {sender,reader,statusMail,attachment};
}
function params(token,extra={}){return {headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...extra}};}
function good(r){const ok=r.status>=200&&r.status<300;unexpected.add(!ok&&r.status!==429);if(r.status===429)expected429.add(1);if(ok)success.add(1);return ok;}
export default function(data){
 const mode=__ENV.MODE||'mixed';const i=execution.scenario.iterationInTest;
 if(mode==='list'||(mode==='mixed'&&i%10<4)){good(http.get(base+'/api/mail/inbox',params(data.reader)));return;}
 if(mode==='attachment'){good(http.get(base+`/api/attachment/download/${data.attachment}`,params(data.reader)));return;}
 if(mode==='status'){good(http.get(base+`/api/mail/${data.statusMail}/status`,params(data.sender)));return;}
 if(mode==='search'){good(http.get(base+'/api/mail/search?userId=1000000001&keyword=seed42',params(data.reader)));return;}
 if(mode==='detail'||(mode==='mixed'&&i%10<8)){good(http.get(base+'/api/mail/detail/1000000000',params(data.reader)));return;}
 if(mode==='mixed'&&i%10===8){good(http.put(base+'/api/mail/read/1000000000',null,params(data.reader)));return;}
 const key=`load-${__ENV.RUN||'local'}-${__VU}-${__ITER}`;const started=Date.now();
 const payload=JSON.stringify({to:[2,3,4,5,6].map(n=>`bench${n}@example.test`),subject:'local load verification',content:'fixed seed payload',attachmentIds:[]});
 let result=http.post(base+'/api/mail/send',payload,params(data.sender,{'Idempotency-Key':key}));
 for(let retry=0;retry<2&&[0,502,504].includes(result.status);retry++){sleep(.2);result=http.post(base+'/api/mail/send',payload,params(data.sender,{'Idempotency-Key':key}));}
 submitMs.add(Date.now()-started);
 if([0,502,504].includes(result.status))console.log('UNCERTAIN KEY '+key);
 if(!good(result))return;
 acceptedSubmissions.add(1);
 const id=result.json('data.mailId');console.log('ACCEPTED '+id+' KEY '+key);
 for(let n=0;n<120;n++){
   sleep(1);const r=http.get(base+`/api/mail/${id}/status`,params(data.sender));if(!good(r))continue;
   const status=r.json('data.status');if(status==='DELIVERED'){e2eMs.add(Date.now()-started);return;}
   if(status==='FAILED'){deliveryFailures.add(1);return;}
 }
 deliveryFailures.add(1);
}
export function handleSummary(data){return {'/results/summary.json':JSON.stringify(data,null,2)};}
