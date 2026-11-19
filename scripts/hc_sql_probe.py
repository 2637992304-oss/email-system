"""Measure list SQL count in the isolated database; restores logging settings.
Run with no concurrent test load. Only SELECT statements against list/user tables are exported.
"""
import subprocess,json,re,uuid
import hc_verify as h

def root(query):
    p=subprocess.run(h.COMPOSE+['exec','-T','db','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot'],input=query,cwd=h.ROOT,text=True,encoding='utf-8',capture_output=True,check=True)
    return p.stdout.strip()
code,login,_=h.api('/api/auth/login',{'email':'bench0@example.test','password':'LocalBench42!'});assert code==200
token=login['data']['token']
settings=root('SELECT @@GLOBAL.general_log,@@GLOBAL.log_output;').split('\t')
assert settings[0]=='0','Do not modify an already-running general log'
marker=uuid.uuid4().hex
try:
    root("SET GLOBAL log_output='TABLE';SET GLOBAL general_log=ON;SELECT '"+marker+"';")
    code,result,_=h.api('/api/mail/sent',token=token);assert code==200
finally:root("SET GLOBAL general_log=OFF;SET GLOBAL log_output='"+settings[1]+"';")
statement = "SELECT argument FROM mysql.general_log WHERE event_time>(SELECT MIN(event_time) FROM mysql.general_log WHERE argument=CONCAT('SELECT ',CHAR(39),'"+marker+"',CHAR(39))) AND command_type='Query' AND argument LIKE 'SELECT%';"
rows=root(statement).splitlines()
rows=[q.replace('\\n',' ').replace('\\t',' ') for q in rows]
selected=[q for q in rows if re.search(r'\bFROM\s+`?(?:user|mail|mail_receiver)`?(?:\s|$)',q,re.I)]
result={'endpoint':'/api/mail/sent?size=20','returned':len(result['data']['items']),'list_related_selects':len(selected),'sql':selected,'baseline_formula':'2+2N SELECTs from original service (includes user lookup); source snapshot retained'}
assert len(selected)==3,result
(h.ROOT/'docs/evidence/sql-count.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
