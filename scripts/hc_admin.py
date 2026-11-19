"""Local-only administration. Operates exclusively on the email-hc database."""
import argparse,json
from hc_verify import sql
p=argparse.ArgumentParser();p.add_argument('command',choices=['failed','replay','audit','fault']);p.add_argument('--mail',type=int);p.add_argument('--phase',choices=['processing_failure','processing_claimed','consumer_committed','publish_confirmed']);p.add_argument('--count',type=int,default=1)
a=p.parse_args()
if a.command=='failed':print(sql("SELECT mail_id,status,attempt,failure FROM delivery_task WHERE status='FAILED';"))
elif a.command=='replay':
    if not a.mail or a.mail<1:p.error('--mail must be positive')
    print(sql(f"START TRANSACTION; SELECT mail_id FROM delivery_task WHERE mail_id={a.mail} FOR UPDATE; INSERT INTO mail_outbox(mail_id,kind,attempt) SELECT mail_id,'DELIVERY',0 FROM delivery_task WHERE mail_id={a.mail} AND status='FAILED'; UPDATE delivery_task SET status='PENDING',attempt=0,lease_until=NULL,failure=NULL WHERE mail_id={a.mail} AND status='FAILED'; UPDATE mail SET delivery_status='PENDING' WHERE mail_id={a.mail} AND EXISTS(SELECT 1 FROM delivery_task t WHERE t.mail_id=mail.mail_id AND t.status='PENDING'); COMMIT;"))
elif a.command=='audit':
    print(sql("SELECT status,COUNT(*) FROM delivery_task GROUP BY status; SELECT mail_id,receiver_id,COUNT(*) FROM mail_receiver GROUP BY mail_id,receiver_id HAVING COUNT(*)>1; SELECT t.mail_id,t.status,JSON_LENGTH(t.recipients) expected,COUNT(r.id) actual FROM delivery_task t LEFT JOIN mail_receiver r ON r.mail_id=t.mail_id GROUP BY t.mail_id HAVING (t.status='DELIVERED' AND expected<>actual) OR (t.status='FAILED' AND actual<>0);"))
else:
    if not a.phase or a.count<0:p.error('--phase and nonnegative --count required')
    print(sql(f"INSERT INTO delivery_test_fault(phase,remaining) VALUES ('{a.phase}',{a.count}) ON DUPLICATE KEY UPDATE remaining=VALUES(remaining);"))
