"""Check deployed web entry, SSE and persistence using synthetic data.

Run setup, recreate the containers without deleting volumes, then run verify.
The state file contains temporary login tokens; keep it private and out of Git.
"""
import argparse, json, pathlib, secrets, urllib.request, uuid

p=argparse.ArgumentParser()
p.add_argument('--base-url',default='http://localhost:5173')
p.add_argument('--phase',choices=['setup','verify'],required=True)
p.add_argument('--state-file',default='data/deployment-state.json')
a=p.parse_args();base=a.base_url.rstrip('/');state=pathlib.Path(a.state_file)

def request(method,path,token=None,body=None,raw=False,content_type='application/json'):
    headers={'Content-Type':content_type}
    if token:headers['Authorization']='Bearer '+token
    data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers,method=method),timeout=30) as r:
        payload=r.read()
    if raw:return payload
    result=json.loads(payload);assert result['code']==200,f'Application failure at {path}'
    return result['data']

if a.phase=='setup':
    assert b'<html' in request('GET','/',raw=True)
    assert b'<html' in request('GET','/login',raw=True)
    print('PASS: web entry and SPA route fallback',flush=True)
    users=[]
    for name in ['sender','receiver']:
        email=f'{name}-{uuid.uuid4().hex[:10]}@example.com';password=secrets.token_urlsafe(16)
        request('POST','/api/auth/register',body={'email':email,'username':name,'password':password})
        login=request('POST','/api/auth/login',body={'email':email,'password':password})
        users.append((email,login['token']))
    sender,receiver=users
    stream=urllib.request.urlopen(urllib.request.Request(base+'/api/mail/notification/stream',headers={'Authorization':'Bearer '+receiver[1]}),timeout=20)
    def event():
        lines=[]
        while True:
            line=stream.readline().decode().strip()
            if not line and lines:return lines
            if line:lines.append(line)
    assert 'event:connected' in event()
    mail=request('POST','/api/mail/send',sender[1],{'to':[receiver[0]],'subject':'Deployment acceptance','content':'Synthetic project meeting notes.'})['mailId']
    notification=event();stream.close()
    assert 'event:new-mail' in notification
    payload=json.loads(next(l[5:] for l in notification if l.startswith('data:')))
    assert payload['mailId']==mail
    print('PASS: authenticated SSE connected and delivered matching new-mail event through web proxy',flush=True)
    boundary=uuid.uuid4().hex;content=b'container persistence acceptance\n'
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="persistent.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode()+content+f'\r\n--{boundary}--\r\n'.encode())
    attachment=request('POST',f'/api/attachment/upload?mailId={mail}',sender[1],body,content_type='multipart/form-data; boundary='+boundary)
    assert request('GET',f'/api/attachment/download/{attachment["attachmentId"]}',receiver[1],raw=True)==content
    state.parent.mkdir(parents=True,exist_ok=True)
    state.write_text(json.dumps({'token':receiver[1],'mail':mail,'attachment':attachment['attachmentId']}),encoding='utf-8')
    print('PASS: synthetic mail and attachment stored; private state saved for recreation check',flush=True)
else:
    saved=json.loads(state.read_text(encoding='utf-8'));token=saved['token']
    assert request('GET',f'/api/mail/detail/{saved["mail"]}',token)['subject']=='Deployment acceptance'
    assert request('GET',f'/api/attachment/download/{saved["attachment"]}',token,raw=True)==b'container persistence acceptance\n'
    print('PASS: existing JWT, database mail and exact attachment bytes survive container recreation',flush=True)
