"""Summarize actual per-endpoint timings from k6 raw JSON without persisting URLs."""
import gzip,json,re,statistics,argparse,datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def quantile(values,p):
    values.sort();x=(len(values)-1)*p;i=int(x);return values[i]+(values[min(i+1,len(values)-1)]-values[i])*(x-i)
def endpoint(url):
    path=url.split('?',1)[0].split('/api/',1)[-1]
    return re.sub(r'/\d+(?=/|$)','/:id',path)
parser=argparse.ArgumentParser();parser.add_argument('--pattern',default='*release*');args=parser.parse_args()
for run in sorted((ROOT/'docs/evidence/load').glob(args.pattern)):
    raw=run/'raw.json.gz'
    if not raw.exists() or not (run/'run.json').exists():continue
    groups={};load_times=[]
    with gzip.open(raw,'rt',encoding='utf-8') as f:
        for line in f:
            point=json.loads(line)
            if point.get('type')!='Point' or point.get('metric')!='http_req_duration':continue
            data=point['data'];tags=data.get('tags',{})
            if tags.get('scenario')=='load':load_times.append(datetime.datetime.fromisoformat(data['time'].replace('Z','+00:00')).timestamp())
            key=tags.get('method','')+' '+endpoint(tags.get('url',''))
            group=groups.setdefault(key,{'durations':[],'statuses':{}});group['durations'].append(data['value'])
            status=tags.get('status','unknown');group['statuses'][status]=group['statuses'].get(status,0)+1
    result={key:{'count':len(g['durations']),'statuses':g['statuses'],'p50_ms':quantile(g['durations'],.5),'p95_ms':quantile(g['durations'],.95),'p99_ms':quantile(g['durations'],.99)} for key,g in groups.items()}
    (run/'endpoints.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    load_times.sort()
    if len(load_times)>1:
        gap=max(b-a for a,b in zip(load_times,load_times[1:]))
        continuity={'load_http_points':len(load_times),'load_span_seconds':load_times[-1]-load_times[0],'largest_load_timestamp_gap_seconds':gap,'requires_continuity_review':gap>30}
        (run/'raw-continuity.json').write_text(json.dumps(continuity,indent=2),encoding='utf-8')
    print(run.name,flush=True)
