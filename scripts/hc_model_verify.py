"""Confirm serialized model learning and equal committed versions in both instances."""
import hc_verify as h
from hc_faults import eventually,docker
import urllib.request,json,uuid
h.ready();_,token,_=h.account('modelsender');receiver,rtoken,_=h.account('modelreceiver')
code,result,_=h.api('/api/mail/send',dict(to=[receiver],subject='Integration check',content='Deterministic local verification',attachmentIds=[]),token,uuid.uuid4().hex);assert code==202,result
mail=result['data']['mailId'];assert h.wait_delivery(mail,token)=='DELIVERED'
version=int(h.sql('SELECT version FROM model_version WHERE id=1;'))
code,result,_=h.api(f'/api/mail/{mail}/report-not-spam',{},rtoken);assert code==200,result
eventually(lambda:int(h.sql('SELECT version FROM model_version WHERE id=1;'))>version)
eventually(lambda:h.sql('SELECT COUNT(*) FROM model_job WHERE done=FALSE;')=='0')
version=int(h.sql('SELECT version FROM model_version WHERE id=1;'))
def versions():
    result=[]
    for port in [18081,18082]:
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/health',timeout=10) as response:result.append(int(response.headers.get('X-Model-Version','-1')))
    return result
assert eventually(lambda:versions()==[version,version])
checksums=[docker('exec','-T',s,'sha256sum',f'/app/data/models/model-{version}.json').split()[0] for s in ['backend','backend-b']]
assert checksums[0]==checksums[1]
result={'mail':mail,'previous_version':version-1,'committed_version':version,'instance_versions':versions(),'snapshot_sha256':checksums,'pending_learning_jobs':0}
(h.ROOT/'docs/evidence/models.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
