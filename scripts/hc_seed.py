"""Deterministic SQL fixtures, seed 42; inserts only reserved benchmark IDs.
Run --count 100000, then --count 1000000 to extend the same dataset.
"""
import argparse,hashlib,time,json,os
from hc_verify import sql,ROOT
p=argparse.ArgumentParser();p.add_argument('--count',type=int,choices=[100000,1000000],default=100000);a=p.parse_args()
password=hashlib.sha256(os.getenv('HC_BENCH_PASSWORD','LocalBench42!').encode()).hexdigest()
started=time.monotonic()
query=f"""
CREATE TEMPORARY TABLE seq(n INT PRIMARY KEY);
INSERT INTO seq WITH digits AS (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e CROSS JOIN digits f;
INSERT IGNORE INTO user(user_id,email,username,password) SELECT 1000000000+n,CONCAT('bench',n,'@example.test'),CONCAT('bench',n),'{password}' FROM seq WHERE n<=100;
SET @existing=(SELECT COUNT(*) FROM mail WHERE mail_id>=1000000000 AND mail_id<1001000000);
INSERT INTO mail(mail_id,sender_id,subject,content,send_time) SELECT 1000000000+n,1000000000,CONCAT('seed42 message ',n),REPEAT('local benchmark content ',6),TIMESTAMPADD(SECOND,n,'2026-01-01 00:00:00') FROM seq WHERE n>=@existing AND n<{a.count};
INSERT INTO mail_receiver(mail_id,receiver_id,receiver_type,receive_time,mail_send_time) SELECT 1000000000+n,IF(n<100000,1000000001,1000000002+MOD(n*42,99)),'TO',TIMESTAMPADD(SECOND,n,'2026-01-01 00:00:00'),TIMESTAMPADD(SECOND,n,'2026-01-01 00:00:00') FROM seq WHERE n>=@existing AND n<{a.count};
ALTER TABLE mail AUTO_INCREMENT=2000000000;
SELECT COUNT(*) FROM mail WHERE mail_id>=1000000000 AND mail_id<1001000000;
SELECT COUNT(*) FROM mail_receiver WHERE receiver_id=1000000001;
ANALYZE TABLE mail,mail_receiver;
"""
result=sql(query)
evidence={'seed':42,'requested_count':a.count,'result':result,'elapsed_seconds':round(time.monotonic()-started,3)}
(ROOT/f'docs/evidence/seed-{a.count}.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8');print(json.dumps(evidence,indent=2))
