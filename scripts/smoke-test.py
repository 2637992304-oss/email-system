"""Integration checks against an isolated running instance; creates synthetic accounts/mail."""
import argparse, json, secrets, time, urllib.request, urllib.error, uuid

p = argparse.ArgumentParser()
p.add_argument('--base-url', default='http://localhost:8080')
a = p.parse_args()
base = a.base_url.rstrip('/')
passed = []

def req(method, path, token=None, body=None, status=200, raw=False, content_type=None):
    headers = {}
    if token: headers['Authorization'] = 'Bearer ' + token
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    if data is not None: headers['Content-Type'] = content_type or 'application/json'
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            code, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        code, payload = error.code, error.read()
    assert code == status, f'{method} {path}: expected {status}, got {code}'
    if status != 200 or raw: return payload
    value = json.loads(payload)
    assert value['code'] == 200, f'{method} {path}: application code {value["code"]}'
    return value['data']

def ok(name):
    passed.append(name)
    print('PASS:', name, flush=True)

for _ in range(60):
    try:
        req('GET', '/api/health');break
    except (OSError, AssertionError):time.sleep(1)
else:raise RuntimeError('Backend not ready')
ok('health')
req('GET', '/api/mail/inbox', status=401)
ok('authentication required')
suffix = uuid.uuid4().hex[:10]
users = []
for name in ['alice','bob','carol']:
    email = f'{name}-{suffix}@example.com'
    password = secrets.token_urlsafe(16)
    req('POST','/api/auth/register',body={'email':email,'username':name,'password':password})
    login=req('POST','/api/auth/login',body={'email':email,'password':password})
    users.append((email,login['token'],login['userId']))
ok('register and login three accounts')
alice,bob,carol=users
req('GET','/api/user/me',bob[1])
mail=req('POST','/api/mail/send',alice[1],{'to':[bob[0]],'bcc':[carol[0]],'subject':'Project meeting '+suffix,'content':'Please review the project notes for our meeting tomorrow.'})['mailId']
assert any(m['mailId']==mail for m in req('GET','/api/mail/inbox',bob[1])['items'])
assert any(m['mailId']==mail for m in req('GET','/api/mail/sent',alice[1])['items'])
detail=req('GET',f'/api/mail/detail/{mail}',bob[1]);assert not detail.get('bcc')
assert any(m['mailId']==mail for m in req('GET','/api/mail/inbox',carol[1])['items'])
ok('send, inbox, sent, detail and BCC privacy')
req('PUT',f'/api/mail/read/{mail}',bob[1]);req('PUT',f'/api/mail/unread/{mail}',bob[1])
req('POST',f'/api/mail/star/{mail}',bob[1]);assert req('GET',f'/api/mail/star/{mail}/check',bob[1])['starred']
req('DELETE',f'/api/mail/star/{mail}',bob[1])
ok('read/unread and star/unstar')
req('POST','/api/mail/contact-list',bob[1],{'contactEmail':alice[0],'listType':'VIP'})
contact=next(c for c in req('GET','/api/mail/contact-list',bob[1]) if c['contactEmail']==alice[0])
req('DELETE',f'/api/mail/contact-list/{contact["id"]}',carol[1],status=403)
req('DELETE',f'/api/mail/contact-list/{contact["id"]}',bob[1])
ok('contact add/list/remove and ownership isolation')
boundary=uuid.uuid4().hex
content=b'synthetic attachment for migration acceptance\n'
multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="notes.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode()+content+f'\r\n--{boundary}--\r\n'.encode())
attachment=req('POST',f'/api/attachment/upload?mailId={mail}',alice[1],multipart,content_type='multipart/form-data; boundary='+boundary)
assert req('GET',f'/api/attachment/download/{attachment["attachmentId"]}',bob[1],raw=True)==content
req('GET',f'/api/attachment/mail/{mail}',bob[1])
ok('attachment upload/list/download')
private=req('POST','/api/mail/send',alice[1],{'to':[bob[0]],'subject':'Private notes','content':'Internal project planning notes.'})['mailId']
req('GET',f'/api/mail/detail/{private}',carol[1],status=403)
req('GET',f'/api/attachment/mail/{private}',carol[1],status=403)
ok('mail and attachment access isolation')
req('GET','/api/mail/notification?userId='+str(bob[2]),bob[1])
req('POST','/api/mail/organize',bob[1],{'folderKey':'inbox'})
ok('notification query and priority sorting')
req('GET','/api/mail/dl-model-status',bob[1])
req('POST',f'/api/mail/{mail}/report-spam',bob[1])
req('POST',f'/api/mail/{mail}/report-not-spam',bob[1])
req('GET',f'/api/mail/{mail}/dl-analysis',bob[1])
ok('local spam model, feedback and analysis')
req('DELETE',f'/api/mail/{mail}',bob[1]);assert any(m['mailId']==mail for m in req('GET','/api/mail/trash',bob[1])['items'])
req('PUT',f'/api/mail/restore/{mail}',bob[1])
assert any(m['mailId']==mail for m in req('GET','/api/mail/inbox',bob[1])['items'])
ok('trash and restore')
print(f'{len(passed)} integration groups passed. Synthetic records remain in the test database.')
