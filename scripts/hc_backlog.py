"""Measure outage backlog recovery with high throughput rate limits configured."""
import time,uuid,json
import hc_verify as h
from hc_faults import docker,eventually
h.ready();code,result,_=h.api('/api/auth/login',{'email':'bench0@example.test','password':'LocalBench42!'});assert code==200
token=result['data']['token'];ids=[]
docker('stop','rabbitmq')
try:
    for i in range(50):
        code,r,_=h.api('/api/mail/send',{'to':['bench2@example.test'],'subject':'backlog recovery fixture','content':'fixed recovery payload','attachmentIds':[]},token,'backlog-'+uuid.uuid4().hex)
        assert code==202,(code,r);ids.append(r['data']['mailId'])
    pending=int(h.sql("SELECT COUNT(*) FROM delivery_task WHERE mail_id IN ("+','.join(map(str,ids))+") AND status='PENDING';"))
    assert pending==50,pending
finally:
    start=time.monotonic();docker('start','rabbitmq')
for mail in ids:assert h.wait_delivery(mail,token,180)=='DELIVERED'
result={'accepted':ids,'backlog_while_broker_stopped':pending,'client_observed_restart_to_all_delivered_seconds':round(time.monotonic()-start,3),'delivered':50}
(h.ROOT/'docs/evidence/backlog.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
