"""Drop an actual committed 202 response, then retry the original key across instances."""
import json,threading,uuid,urllib.request,socket
from http.server import HTTPServer,BaseHTTPRequestHandler
import hc_verify as h
h.ready();_,token,user=h.account('response-sender');receiver,rtoken,_=h.account('response-receiver')
payload={'to':[receiver],'subject':'Integration check','content':'Deterministic local verification','attachmentIds':[]};key=uuid.uuid4().hex
forwarded={};done=threading.Event()
class DropResponse(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_POST(self):
        body=self.rfile.read(int(self.headers['Content-Length']))
        req=urllib.request.Request(h.BASE+'/api/mail/send',data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':key})
        with urllib.request.urlopen(req,timeout=30) as response:
            forwarded.update({'code':response.status,'result':json.load(response),'backend':response.headers.get('X-Backend')})
        done.set();self.close_connection=True
        self.connection.shutdown(socket.SHUT_RDWR);self.connection.close()
server=HTTPServer(('127.0.0.1',0),DropResponse);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
try:
    try:
        urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+str(server.server_port)+'/send',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'}),timeout=30)
        raise AssertionError('The deliberately dropped response reached the client')
    except (OSError,ConnectionError):pass
    assert done.wait(5),forwarded
    assert forwarded['code']==202,forwarded
    original=forwarded['result']['data']['mailId'];backends={forwarded['backend']}
    for _ in range(4):
        code,result,backend=h.api('/api/mail/send',payload,token,key);assert code==202,result;assert result['data']['mailId']==original,result;backends.add(backend)
    assert len(backends)==2,backends
    assert h.wait_delivery(original,token)=='DELIVERED'
    assert h.sql(f'SELECT COUNT(*) FROM mail_receiver WHERE mail_id={original};')=='1'
    result={'original_response':'dropped_after_upstream_202','idempotency_key':key,'mail':original,'retry_mail_ids_equal':True,'backend_count':len(backends),'receiver_rows':1,'status':'DELIVERED'}
    (h.ROOT/'docs/evidence/response-replay.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
finally:server.shutdown();server.server_close();thread.join(timeout=5)
