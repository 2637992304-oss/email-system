"""Summarize retained docker stats without rerunning workloads."""
import json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def mib(text):
    m=re.match(r'([\d.]+)([KMGT]?i?B)',text.strip())
    if not m:return None
    return float(m[1])*{'B':1/1048576,'kB':1/1024,'KB':1/1024,'KiB':1/1024,'MB':1,'MiB':1,'GB':1024,'GiB':1024,'TB':1048576,'TiB':1048576}.get(m[2],1)
for run in (ROOT/'docs/evidence/load').iterdir():
    path=run/'resources.jsonl'
    if not path.exists() or not (run/'run.json').exists():continue
    groups={}
    for line in path.read_text(encoding='utf-8').splitlines():
        sample=json.loads(line)
        for row in sample.get('stats','').splitlines():
            stat=json.loads(row);key=stat['Name'];g=groups.setdefault(key,{'cpu':[],'memory':[]})
            g['cpu'].append(float(stat['CPUPerc'].strip('%')));value=mib(stat['MemUsage'].split('/')[0])
            if value is not None:g['memory'].append(value)
    result={k:{'samples':len(v['cpu']),'cpu_mean_percent_one_core':sum(v['cpu'])/len(v['cpu']),'cpu_max_percent_one_core':max(v['cpu']),'memory_max_mib':max(v['memory']) if v['memory'] else None} for k,v in groups.items() if v['cpu']}
    (run/'resource-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
