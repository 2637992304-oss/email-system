"""Regress cursor pages, persisted priorities, flags, search/filter and spam routing."""
import json,uuid,urllib.parse
from xml.etree import ElementTree
import hc_verify as h
h.ready();receiver,rtoken,ruser=h.account('functional-receiver');prefix='check-'+uuid.uuid4().hex;ids=[];senders=[]
for n in range(7):
    sender,token,user=h.account('functional'+str(n));senders.append((token,user))
    for i in range(3):
        code,result,_=h.api('/api/mail/send',{'to':[receiver],'subject':prefix+'-'+str(n*3+i),'content':'Deterministic local verification','attachmentIds':[]},token,uuid.uuid4().hex)
        assert code==202,result;ids.append(result['data']['mailId'])
        assert h.wait_delivery(ids[-1],token)=='DELIVERED'
for mail in ids:
    assert h.api('/api/mail/star/'+str(mail),{},rtoken)[0]==200
assert h.api('/api/mail/detail/'+str(ids[1]),token=rtoken)[0]==200
rows=[];cursor=None
while True:
    path='/api/mail/inbox?size=2'+('&cursor='+urllib.parse.quote(cursor) if cursor else '')
    code,result,_=h.api(path,token=rtoken);assert code==200,result
    page=result['data'];rows.extend(page['items'])
    if not page['hasMore']:break
    cursor=page['nextCursor']
assert len(rows)==21 and {m['mailId'] for m in rows}==set(ids)
assert all(m['starred'] for m in rows)
code,result,_=h.api('/api/mail/starred',token=rtoken);assert code==200
assert len(result['data']['items'])==20 and result['data']['hasMore']
read=[m for m in rows if m.get('isRead')];assert [m['mailId'] for m in read]==[ids[1]],read
code,result,_=h.api('/api/mail/filter?'+urllib.parse.urlencode({'userId':ruser,'folder':'inbox','isRead':'true'}),token=rtoken);assert code==200,result
assert [m['mailId'] for m in result['data']['items']]==[ids[1]]
code,result,_=h.api('/api/mail/filter?'+urllib.parse.urlencode({'userId':ruser,'folder':'inbox','startTime':'2099-01-01T00:00:00'}),token=rtoken);assert code==200 and result['data']['items']==[],result
code,result,_=h.api('/api/mail/search?'+urllib.parse.urlencode({'userId':ruser,'keyword':prefix}),token=rtoken);assert code==200,result
assert result['data']['hasMore'] and all(m['senderName'] and m['senderEmail'] for m in result['data']['items'])
search_ids = []
cursor = None
while True:
    search_params = {'userId': ruser, 'keyword': prefix, 'size': 2}
    if cursor:
        search_params['cursor'] = cursor
    code, response, _ = h.api('/api/mail/search?' + urllib.parse.urlencode(search_params), token=rtoken)
    assert code == 200, response
    page = response['data']
    search_ids.extend(m['mailId'] for m in page['items'])
    if not page['hasMore']:
        break
    cursor = page['nextCursor']
assert search_ids == sorted(ids, reverse=True), search_ids
code, response, _ = h.api('/api/mail/search?' + urllib.parse.urlencode({'userId': ruser, 'keyword': prefix}), token=senders[0][0])
assert code == 403, response
assert h.sql('SELECT COUNT(*) FROM user_behavior WHERE behavior_type=0x53454e44 AND mail_id IN (' + ','.join(map(str,ids)) + ');') == '21'

code,result,_=h.api('/api/mail/organize',{'folderKey':'inbox'},rtoken);assert code==200,result
first=result['data'];assert len(first['items'])==20 and first['hasMore'],first
code,result,_=h.api('/api/mail/organize',{'folderKey':'inbox','cursor':first['nextCursor']},rtoken);assert code==200,result
all_priority=first['items']+result['data']['items'];assert len(all_priority)==21 and {m['mailId'] for m in all_priority}==set(ids)
assert next(m for m in all_priority if m['mailId']==ids[1])['isRead'] is True
scores=[m['priorityScore'] for m in all_priority];assert scores==sorted(scores,reverse=True)
assert h.api('/api/mail/'+str(ids[0]),token=rtoken,method='DELETE')[0]==200
assert h.api('/api/mail/restore/'+str(ids[0]),token=rtoken,method='PUT')[0]==200
last_token,last_user=senders[-1]
code,result,_=h.api('/api/mail/send',{'to':[receiver],'subject':'免费中奖领取','content':'spam routing fixture','attachmentIds':[]},last_token,uuid.uuid4().hex);assert code==202,result
spam=result['data']['mailId'];assert h.wait_delivery(spam,last_token)=='DELIVERED'
code,result,_=h.api('/api/mail/spam',token=rtoken);assert code==200 and any(m['mailId']==spam for m in result['data']['items'])
other_receiver, _, other_user = h.account('functional-warning-receiver')
code,result,_=h.api('/api/mail/send',{'to':[receiver,other_receiver],'subject':'免费中奖领取','content':'bulk warning fixture','attachmentIds':[]},last_token,uuid.uuid4().hex)
assert code==202,result
warning_mail=result['data']['mailId']
assert h.wait_delivery(warning_mail,last_token)=='DELIVERED'
warnings = h.sql(
    f"SELECT recipient_id FROM spam_warning WHERE mail_id={warning_mail} AND status='PENDING' ORDER BY recipient_id;")
assert set(map(int,warnings.splitlines())) == {ruser, other_user},warnings
assert h.sql(f"SELECT COUNT(*) FROM spam_warning WHERE mail_id={spam};") == '0'
code,result,_=h.api('/api/mail/send',{'to':[f'bench{n}@example.test' for n in range(101)],'subject':'recipient limit','content':'fixture','attachmentIds':[]},last_token,uuid.uuid4().hex);assert code==400,result
code,response,_=h.api('/api/mail/send',{'to':[receiver],'subject':prefix+'-self','content':'Self-addressed search fixture','attachmentIds':[]},rtoken,uuid.uuid4().hex)
assert code==202,response
self_id=response['data']['mailId']
assert h.wait_delivery(self_id,rtoken)=='DELIVERED'
code,response,_=h.api('/api/mail/search?'+urllib.parse.urlencode({'userId':ruser,'keyword':prefix+'-self'}),token=rtoken)
assert code==200 and [m['mailId'] for m in response['data']['items']]==[self_id],response
out={'search_cursor_complete': True, 'foreign_search_forbidden': True, 'bulk_spam_warning_replaces_previous': True, 'self_addressed_search_deduplicated': True, 'send_behaviors_persisted': 21, 'unique_inbox_mails':21,'star_flags_preserved':21,'priority_pages':[20,1],'priority_read_flag_preserved':True,'filtered_read_count':1,'future_date_filter_count':0,'search_metadata_complete':True,'soft_delete_restore':True,'spam_delivered_to_spam_box':spam,'receiver_limit_status':400}
mapper = ElementTree.parse(h.ROOT/'backend/src/main/resources/mapper/MailSearchMapper.xml').getroot()
statement = mapper.find("select[@id='search']")
keyword_fragment = mapper.find("sql[@id='SearchKeyword']").text
parts = [statement.text]
for child in statement:
    if child.tag == 'include':
        parts.append(keyword_fragment)
    parts.append(child.tail)
explain_sql = ''.join(parts).replace('#{userId}', '1000000001').replace('#{keyword}', "'seed42'").replace('#{limit}', '21')
plan = h.sql('EXPLAIN ANALYZE ' + explain_sql + ';')
(h.ROOT/'docs/evidence/search-ordered-plan.json').write_text(
    json.dumps({'sql': explain_sql, 'plan': plan}, indent=2), encoding='utf-8')
(h.ROOT/'docs/evidence/functional.json').write_text(json.dumps(out,indent=2),encoding='utf-8');print(json.dumps(out,indent=2))
