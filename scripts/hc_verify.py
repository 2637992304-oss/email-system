"""Reproducible integration checks against the isolated email-hc Compose project.
No credentials or tokens are written to the evidence directory.
"""
import concurrent.futures, json, os, subprocess, time, uuid, urllib.request, urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BASE=os.getenv('HC_BASE_URL','http://127.0.0.1:5180')
COMPOSE=['docker','compose','-f',str(ROOT/'compose.high-concurrency.yaml')]
def docker(*args):
    return subprocess.run(COMPOSE+list(args),cwd=ROOT,text=True,encoding='utf-8',capture_output=True,check=True).stdout

def sql(query):
    p=subprocess.run(COMPOSE+['exec','-T','db','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B -u "$MYSQL_USER" mail_system'],input=query,cwd=ROOT,text=True,encoding='utf-8',capture_output=True,check=True)
    return p.stdout.strip()

def api(path,body=None,token=None,key=None,method=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as r: return r.status,json.load(r),r.headers.get('X-Backend')
    except urllib.error.HTTPError as e:
        raw=e.read()
        try: result=json.loads(raw)
        except ValueError: result={'message':'HTTP '+str(e.code)}
        return e.code,result,e.headers.get('X-Backend')

def account(prefix):
    email=prefix+'-'+uuid.uuid4().hex[:10]+'@example.test';password=uuid.uuid4().hex[:20]+'Aa1!'
    code,_,_=api('/api/auth/register',dict(email=email,username=prefix,password=password))
    assert code==200,code
    code,result,_=api('/api/auth/login',dict(email=email,password=password));assert code==200,result
    return email,result['data']['token'],result['data']['userId']

def wait_delivery(mail,token,timeout=100):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        code,result,_=api(f'/api/mail/{mail}/status',token=token)
        if code in (502,503,504):
            time.sleep(.5);continue
        assert code==200,result
        status=result['data']['status']
        if status in ('DELIVERED','FAILED'): return status
        time.sleep(.5)
    raise AssertionError('Delivery timeout: '+str(mail))

def ready():
    until=time.monotonic()+90
    consecutive=0
    while time.monotonic()<until:
        try: code,_,_=api('/api/health')
        except Exception: code=0
        consecutive=consecutive+1 if code==200 else 0
        if consecutive>=4:return
        time.sleep(1)
    raise RuntimeError('Backends did not become ready')

def run():
    ready()
    sender,token,user=account('sender');receiver,rtoken,ruser=account('receiver')
    payload=dict(to=[receiver],cc=[receiver],bcc=[receiver],subject='Integration check',content='Deterministic local verification',attachmentIds=[])
    key=uuid.uuid4().hex; accepted=[]
    started=time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results=list(pool.map(lambda _:api('/api/mail/send',payload,token,key),range(8)))
    assert all(c==202 for c,_,_ in results),results
    ids={r['data']['mailId'] for _,r,_ in results}; assert len(ids)==1,ids
    mail=ids.pop();accepted.append(mail)
    assert wait_delivery(mail,token)=='DELIVERED'
    count=sql(f'SELECT COUNT(*) FROM mail_receiver WHERE mail_id={mail};');assert count=='1',count
    conflict=api('/api/mail/send',{**payload,'subject':'different'},token,key);assert conflict[0]==409,conflict
    forbidden=api(f'/api/mail/{mail}/status',token=rtoken);assert forbidden[0]==404,forbidden
    code,page,_=api('/api/mail/inbox',token=rtoken);assert code==200,page
    assert any(r['mailId']==mail for r in page['data']['items']),page
    assert api('/api/mail/inbox?cursor=broken',token=rtoken)[0]==400
    # A fresh user starts with exactly ten shared tokens; submit all requests concurrently.
    _, rate_token, _=account('rate')
    with concurrent.futures.ThreadPoolExecutor(max_workers=11) as pool:
        limited=list(pool.map(lambda _:api('/api/mail/send',payload,rate_token,uuid.uuid4().hex),range(11)))
    rate=[c for c,_,_ in limited];backends={b for _,_,b in limited}
    assert rate.count(202)==10 and rate.count(429)==1,rate
    assert len(backends)==2,backends
    rate_ids=[r['data']['mailId'] for c,r,_ in limited if c==202]
    for m in rate_ids: assert wait_delivery(m,rate_token)=='DELIVERED'
    for m in accepted: assert wait_delivery(m,token)=='DELIVERED'
    accepted.extend(rate_ids)
    duplicates=sql('SELECT COUNT(*) FROM (SELECT mail_id,receiver_id,COUNT(*) c FROM mail_receiver GROUP BY mail_id,receiver_id HAVING c>1) t;')
    assert duplicates=='0',duplicates
    evidence={'timestamp':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'accepted':accepted,'concurrent_replays':8,'same_key_conflict':409,'foreign_status':404,'deduplicated_receivers':1,'rate_statuses':rate,'backends':sorted(backends),'duplicates':0,'duration_seconds':round(time.monotonic()-started,3)}
    target=ROOT/'docs/evidence/integration.json';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(evidence,indent=2),encoding='utf-8')
    print(json.dumps(evidence,indent=2))
if __name__=='__main__':run()
