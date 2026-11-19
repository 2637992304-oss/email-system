"""Capture actual EXPLAIN ANALYZE output, without exporting email bodies."""
from hc_verify import sql,ROOT
import json,time,argparse,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--label',default='before-index');a=p.parse_args()
uid=1000000001
base=f"FROM mail_receiver mr JOIN mail m ON mr.mail_id=m.mail_id JOIN user u ON m.sender_id=u.user_id WHERE mr.receiver_id={uid} AND mr.is_deleted=FALSE AND m.mail_status=1"
select="SELECT m.mail_id,m.sender_id,u.username,u.email,m.subject,LEFT(m.content,50),mr.is_read,m.has_attachment,mr.receive_time,m.send_time,m.priority,m.is_spam "
original=ET.parse(ROOT/'docs/evidence/baseline/MailMapper.xml').getroot().find("select[@id='selectInboxList']").text.replace('#{userId}',str(uid))
queries={'original_inbox':original,'paged_inbox':select+base+' AND m.is_spam=FALSE ORDER BY mr.receive_time DESC,mr.mail_id DESC LIMIT 21','paged_sent':"SELECT mail_id,subject,LEFT(content,50),send_time FROM mail WHERE sender_id=1000000000 AND sender_deleted=FALSE AND mail_status=1 ORDER BY send_time DESC,mail_id DESC LIMIT 21"}
results={}
for name,q in queries.items():
    sql('EXPLAIN ANALYZE '+q+';') # Warm-up.
    results[name]={'sql':q,'plans':[sql('EXPLAIN ANALYZE '+q+';') for _ in range(3)]}
(ROOT/f'docs/evidence/query-{a.label}.json').write_text(json.dumps(results,indent=2),encoding='utf-8');print('Saved '+a.label)
