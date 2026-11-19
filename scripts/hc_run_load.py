"""Run reproducible k6 workloads and collect raw server resource samples.
Examples: --label double --mode mixed --rate 100 --duration 10m --repeats 3
No secret/token values are saved to evidence.
"""
import argparse,json,os,subprocess,time,threading,re
from pathlib import Path
from hc_verify import ROOT,COMPOSE,sql
p=argparse.ArgumentParser();p.add_argument('--label',required=True);p.add_argument('--mode',choices=['list','detail','submit','status','search','attachment','mixed'],default='mixed');p.add_argument('--rate',type=int,default=100);p.add_argument('--duration',default='10m');p.add_argument('--repeats',type=int,default=3);a=p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_-]+',a.label):p.error('Invalid label')
for run in range(1,a.repeats+1):
    label=f'{a.label}-{a.mode}-{a.rate}-{run}';out=ROOT/'docs/evidence/load'/label
    if out.exists() and any(out.iterdir()):p.error('Evidence folder already exists; choose a new --label: '+label)
    out.mkdir(parents=True,exist_ok=True)
    stop=threading.Event()
    def sample():
        with (out/'resources.jsonl').open('w',encoding='utf-8') as f:
            while not stop.is_set():
                stats=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}','email-hc-backend-1','email-hc-backend-b-1','email-hc-db-1','email-hc-rabbitmq-1','email-hc-redis-1'],capture_output=True,text=True,encoding='utf-8').stdout
                try:
                    db=sql("SHOW STATUS WHERE Variable_name IN ('Threads_connected','Threads_running'); SELECT status,COUNT(*) FROM delivery_task GROUP BY status; SELECT COUNT(*) FROM mail_outbox WHERE published=FALSE;")
                    queues=subprocess.run(COMPOSE+['exec','-T','rabbitmq','rabbitmqctl','list_queues','name','messages_ready','messages_unacknowledged'],capture_output=True,text=True,encoding='utf-8').stdout
                except Exception as e:db=type(e).__name__;queues=''
                f.write(json.dumps({'at':time.time(),'stats':stats,'db':db,'queues':queues})+'\n');f.flush();stop.wait(10)
    t=threading.Thread(target=sample,daemon=True)
    command=['docker','run','--rm','--label','mail.hc.run='+label,'--network','email-hc_default','-v',str(ROOT/'scripts')+':/scripts:ro','-v',str(out)+':/results','-e','RATE='+str(a.rate),'-e','DURATION='+a.duration,'-e','MODE='+a.mode,'-e','RUN='+label]
    if os.getenv('HC_BENCH_PASSWORD'):command+=['-e','HC_BENCH_PASSWORD']
    command+=['grafana/k6:0.54.0','run','--out','json=/results/raw.json.gz','/scripts/hc_load.js']
    build=subprocess.run(COMPOSE+['exec','-T','backend','sha256sum','/app/app.jar'],capture_output=True,text=True,encoding='utf-8')
    build_hash=build.stdout.split()[0] if build.returncode==0 else None
    instance_state = {}
    for service in ('backend', 'backend-b'):
        info = subprocess.run(
            ['docker', 'inspect', '--format',
             '{{.State.Running}} {{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}',
             f'email-hc-{service}-1'], capture_output=True, text=True, check=False)
        if info.returncode != 0:
            continue
        running, cpu, memory = info.stdout.split()
        instance_state[service] = {
            'running': running == 'true', 'cpu_nanocores': int(cpu), 'memory_bytes': int(memory)}
        if running == 'true':
            values = subprocess.run(
                COMPOSE + ['exec', '-T', service, 'printenv', 'DB_POOL_SIZE', 'CONSUMERS', 'RATE_CAPACITY'],
                capture_output=True, text=True, check=True).stdout.splitlines()
            instance_state[service].update(dict(zip(('db_pool', 'consumers', 'rate_capacity'), map(int, values))))
            instance_state[service]['jar_sha256'] = subprocess.run(
                COMPOSE + ['exec', '-T', service, 'sha256sum', '/app/app.jar'],
                capture_output=True, text=True, check=True).stdout.split()[0]
    (out/'topology.json').write_text(json.dumps(instance_state,indent=2),encoding='utf-8')
    t.start()
    started=time.monotonic()
    try:
        with (out/'console.log').open('w',encoding='utf-8') as log:process_result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,text=True,cwd=ROOT)
    finally:stop.set();t.join(timeout=30)
    accepted=[];uncertain=[]
    for line in (out/'console.log').read_text(encoding='utf-8').splitlines():
        match=re.search(r'ACCEPTED (\d+) KEY ([A-Za-z0-9._:-]+)',line)
        if match:accepted.append(int(match.group(1)))
        missing=re.search(r'UNCERTAIN KEY ([A-Za-z0-9._:-]+)',line)
        if missing:uncertain.append(missing.group(1))
    if uncertain:
        from hc_verify import api
        code,result,_=api('/api/auth/login',{'email':'bench0@example.test','password':os.getenv('HC_BENCH_PASSWORD','LocalBench42!')})
        if code!=200:raise RuntimeError('Reconciliation login failed')
        token=result['data']['token']
        for key in uncertain:
            code,result,_=api('/api/mail/send',{'to':[f'bench{n}@example.test' for n in [2,3,4,5,6]],'subject':'local load verification','content':'fixed seed payload','attachmentIds':[]},token,key)
            if code!=202:raise RuntimeError('Unresolved uncertain request: '+key)
            accepted.append(result['data']['mailId'])
    end=time.monotonic()+300
    while sql("SELECT COUNT(*) FROM delivery_task WHERE status IN ('PENDING','PROCESSING');")!='0' and time.monotonic()<end:time.sleep(2)
    audit=sql("SELECT status,COUNT(*) FROM delivery_task GROUP BY status; SELECT mail_id,receiver_id,COUNT(*) FROM mail_receiver GROUP BY mail_id,receiver_id HAVING COUNT(*)>1;")
    final_states={}
    for offset in range(0,len(accepted),200):
        ids=','.join(map(str,accepted[offset:offset+200]))
        if not ids:continue
        for line in sql(f"SELECT mail_id,status FROM delivery_task WHERE mail_id IN ({ids});").splitlines():
            mid,state=line.split('\t');final_states[mid]=state
    (out/'accepted-final-states.json').write_text(json.dumps(final_states,indent=2),encoding='utf-8')
    if len(final_states)!=len(set(accepted)) or any(v!='DELIVERED' for v in final_states.values()):
        print('WARNING: accepted task reconciliation failed',flush=True)

    (out/'audit.txt').write_text(audit,encoding='utf-8')
    (out/'run.json').write_text(json.dumps({'label':label,'mode':a.mode,'backend_jar_sha256':build_hash,'preallocated_vus':150,'max_vus':500,'rate':a.rate,'duration':a.duration,'exit_code':process_result.returncode,'elapsed_seconds':round(time.monotonic()-started,3),'accepted_count':len(accepted),'accepted':accepted},indent=2),encoding='utf-8')
    print(label+' finished exit='+str(process_result.returncode),flush=True)
    if process_result.returncode not in (0,99):raise SystemExit(process_result.returncode)
