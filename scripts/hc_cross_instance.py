"""Cross-instance SSE and attachment checks with test-only loopback ports."""
import json,uuid,urllib.request,urllib.error,threading,time,hashlib
import hc_verify as h
A='http://127.0.0.1:18081';B='http://127.0.0.1:18082'
def wait_for_test_instances():
    deadline = time.monotonic() + 90
    for base in (A, B):
        while True:
            try:
                with urllib.request.urlopen(base + '/api/health', timeout=3) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError('Test backend did not become ready: ' + base)
            time.sleep(1)

def run():
    h.ready();wait_for_test_instances();sender,token,user=h.account('crosssender');receiver,rtoken,_=h.account('crossreceiver')
    boundary=uuid.uuid4().hex;content=b'cross-instance attachment verification\n'
    raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="check.txt"\r\nContent-Type: text/plain\r\n\r\n').encode()+content+f'\r\n--{boundary}--\r\n'.encode()
    req=urllib.request.Request(A+'/api/attachment/upload',data=raw,headers={'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary})
    with urllib.request.urlopen(req,timeout=30) as response:attachment=json.load(response)['data']['attachmentId']
    connected=threading.Event();received=threading.Event();result={};stream=[None]
    def listen():
        try:
            req=urllib.request.Request(A+'/api/mail/notification/stream',headers={'Authorization':'Bearer '+rtoken})
            with urllib.request.urlopen(req,timeout=100) as response:
                stream[0]=response;event=''
                for line in response:
                    line=line.decode().strip()
                    if line.startswith('event:'):event=line[6:].strip()
                    if line.startswith('data:'):
                        if event=='connected':connected.set()
                        if event=='new-mail':result.update(json.loads(line[5:]));received.set();break
        except Exception as e:result['error']=type(e).__name__;connected.set()
    old=urllib.request.urlopen(urllib.request.Request(A+'/api/mail/notification/stream',headers={'Authorization':'Bearer '+rtoken}),timeout=30)
    thread=threading.Thread(target=listen,daemon=True);thread.start();assert connected.wait(30),result
    old.close()
    h.BASE=B
    try:
        payload=dict(to=[receiver],subject='Integration check',content='Deterministic local verification',attachmentIds=[attachment])
        code,data,_=h.api('/api/mail/send',payload,token,uuid.uuid4().hex);assert code==202,(code,data)
        mail=data['data']['mailId'];assert h.wait_delivery(mail,token)=='DELIVERED'
        assert received.wait(60),result
        assert result['mailId']==mail,result
        req=urllib.request.Request(B+f'/api/attachment/download/{attachment}',headers={'Authorization':'Bearer '+rtoken})
        with urllib.request.urlopen(req,timeout=30) as response: downloaded=response.read()
        assert downloaded==content
        post_send=(f'--{boundary}\r\nContent-Disposition: form-data; name="mailId"\r\n\r\n{mail}\r\n').encode()+raw
        req=urllib.request.Request(B+'/api/attachment/upload',data=post_send,headers={'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary})
        try:
            urllib.request.urlopen(req,timeout=30)
            raise AssertionError('Post-send attachment upload was accepted')
        except urllib.error.HTTPError as e:
            assert e.code==400,e.code
        # A bound attachment cannot be attached to a different submission.
        assert h.api('/api/mail/send',payload,token,uuid.uuid4().hex)[0]==400
        out={'sse_connection':'A','submission_and_consumer':'B','notified_mail':mail,'attachment_uploaded_at':'A','attachment_downloaded_at':'B','sha256':hashlib.sha256(downloaded).hexdigest(),'bound_attachment_reuse_status':400,'post_send_upload_status':400,'old_connection_closed_new_connection_received':True}
        (h.ROOT/'docs/evidence/cross-instance.json').write_text(json.dumps(out,indent=2),encoding='utf-8');print(json.dumps(out,indent=2))
    finally:
        h.BASE='http://127.0.0.1:5180'
        if stream[0]:stream[0].close()
if __name__=='__main__':run()
