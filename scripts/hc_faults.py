"""Run only with compose.hc-test.yaml (faults disabled in normal configuration)."""
import json,time,uuid,subprocess,threading,urllib.request,concurrent.futures
import hc_verify as h
from pathlib import Path
C=h.COMPOSE+['-f',str(h.ROOT/'compose.hc-test.yaml')]
def docker(*args):return subprocess.run(C+list(args),cwd=h.ROOT,text=True,capture_output=True,encoding='utf-8',check=True).stdout
def eventually(fn,timeout=120):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            value=fn()
            if value:return value
        except Exception:pass
        time.sleep(1)
    raise AssertionError('Timed out waiting for fault recovery')
def drain():eventually(lambda:h.sql("SELECT COUNT(*) FROM mail_outbox WHERE published=FALSE;")=='0')
def run():
    h.ready();out={};h.sql('UPDATE delivery_test_fault SET remaining=0;');_,token,_=h.account('faultsender');receiver,rtoken,_=h.account('faultreceiver')
    payload=dict(to=[receiver],subject='local recovery verification',content='fixed recovery payload',attachmentIds=[])
    def submit():
        key=uuid.uuid4().hex;started=time.monotonic();unavailable=0
        while True:
            code,result,_=h.api('/api/mail/send',payload,token,key)
            if code!=503 or time.monotonic()-started>60:break
            unavailable+=1;time.sleep(1)
        assert code==202,(code,result)
        if unavailable:out.setdefault('application_reconnect_waits',[]).append({'responses_503':unavailable,'seconds':round(time.monotonic()-started,3)})
        mail=result['data']['mailId']
        with (h.ROOT/'docs/evidence/fault-accepted.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps({'accepted':mail})+'\n')
        print('accepted '+str(mail),flush=True)
        return mail
    def verify(mail):
        assert h.wait_delivery(mail,token,150)=='DELIVERED'
        assert h.sql(f'SELECT COUNT(*) FROM mail_receiver WHERE mail_id={mail};')=='1'
    # Queue outage: task is accepted durably, then recovers after a backend restart.
    drain();docker('stop','rabbitmq');mail=submit()
    assert h.sql(f"SELECT status FROM delivery_task WHERE mail_id={mail};")=='PENDING'
    docker('restart','backend');recovery_started=time.monotonic();docker('start','rabbitmq');h.ready();verify(mail);out['broker_outage_and_backend_restart']={'mail':mail,'status':'DELIVERED','client_observed_recovery_seconds':round(time.monotonic()-recovery_started,3)}
    # Redis outage: fail closed on new submissions, reads remain usable.
    docker('stop','redis')
    try:
        code,_,_=h.api('/api/mail/send',payload,token,uuid.uuid4().hex);assert code==503,code
        assert h.api('/api/mail/inbox',token=rtoken)[0]==200
        out['redis_outage']={'new_submission':503,'read':200}
    finally:docker('start','redis')
    eventually(lambda:docker('exec','-T','redis','redis-cli','PING').strip()=='PONG')
    # Process exits at durable handover boundaries; restart policy brings the same container back.
    for phase in ['publish_confirmed','consumer_committed','processing_claimed']:
        print('Testing '+phase,flush=True)
        drain();h.sql(f"INSERT INTO delivery_test_fault(phase,remaining) VALUES ('{phase}',1) ON DUPLICATE KEY UPDATE remaining=1;")
        mail=submit();verify(mail);h.ready()
        assert h.sql(f"SELECT remaining FROM delivery_test_fault WHERE phase='{phase}';")=='0'
        out[phase]={'mail':mail,'status':'DELIVERED','receiver_rows':1}
    # Initial failure plus three configured retries must persist FAILED and a dead event.
    drain();h.sql("INSERT INTO delivery_test_fault(phase,remaining) VALUES ('processing_failure',4) ON DUPLICATE KEY UPDATE remaining=4;")
    mail=submit();assert h.wait_delivery(mail,token,150)=='FAILED'
    assert h.sql(f'SELECT attempt FROM delivery_task WHERE mail_id={mail};')=='4'
    eventually(lambda:h.sql(f"SELECT COUNT(*) FROM mail_outbox WHERE mail_id={mail} AND kind='DEAD' AND published=TRUE;")=='1')
    subprocess.run(['python',str(h.ROOT/'scripts/hc_admin.py'),'replay','--mail',str(mail)],check=True,cwd=h.ROOT,capture_output=True)
    verify(mail);out['dead_letter_replay']={'mail':mail,'attempts_before_replay':4,'status':'DELIVERED'}
    # Stop one instance; Nginx routes to the survivor. Recover both before returning.
    docker('stop','backend')
    try:
        assert h.api('/api/mail/inbox',token=rtoken)[0]==200
        out['single_backend_exit']='readable'
    finally:docker('start','backend');h.ready()
    (h.ROOT/'docs/evidence/faults.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
    print(json.dumps(out,indent=2))
if __name__=='__main__':run()
