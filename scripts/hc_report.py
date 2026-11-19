"""Build the report only from retained, actually executed evidence."""
from pathlib import Path
import json,statistics,re,math
from hc_verify import ROOT,sql
E=ROOT/'docs/evidence'
def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def pct(values,p):
    if not values:return None
    values=sorted(values);index=(len(values)-1)*p;lo=int(index);hi=math.ceil(index)
    return values[lo]+(values[hi]-values[lo])*(index-lo)
def val(metrics,key,stat,default=0):return metrics.get(key,{}).get('values',{}).get(stat,default)
lines=['# 高并发改造实测报告','', '本轮实现及验证结果见下方最终构建汇总。可靠性验证与性能阈值分别判读，存在未达标轮次，不宣称全部验收通过。历史测试属于各自记录的构建，不能混作最终版本的正式数据。', '',
 '所有数据来自本地 Docker Compose；不是生产承诺。数据与配置原件见 evidence/。',
 '', '## 环境与固定数据','']
for name in ['host-hardware.json','environment.json','backend-build.json','seed-100000.json','seed-1000000.json']:
    path=E/name
    if path.exists():lines+=['### '+name,'','```json',json.dumps(read(path),ensure_ascii=False,indent=2),'```','']
lines+=['## SQL 对照','','SQL时间来自实际 EXPLAIN ANALYZE，不等同于 API 响应时间。早期 original_inbox 使用等价简化选择列；exact-original 组从保存的原始 Mapper 读取完整原 SQL。','','| 数据/版本 | 查询 | 三次根节点完成时间中位数(ms) |','|---|---|---:|']
for path in sorted(E.glob('query-*.json')):
    for key,data in read(path).items():
        times=[]
        for plan in data['plans']:
            first=plan.replace('\\n','\n').splitlines()[0]
            try:times.append(float(first.split('actual time=')[1].split(' rows=')[0].split('..')[1]))
            except (ValueError,IndexError):pass
        if times:lines.append(f'| {path.stem} | {key} | {statistics.median(times):.3f} |')
lines+=['','索引选择经历了真实验证：仅增加分页和索引时仍发生全量排序；改为按接收记录的 receive_time、mail_id 排序后，收件箱查询使用 idx_receiver_cursor，只读取当前页所需记录。已发送列表由每封两次补查改为当前页一次批量收件人查询。','', '## 正确性与故障实验','']
for name in ['integration.json','functional.json','response-replay.json','cross-instance.json','models.json','faults.json','backlog.json','sql-count.json','final-runtime.json']:
    path=E/name
    if path.exists():lines+=['### '+name,'','```json',json.dumps(read(path),ensure_ascii=False,indent=2),'```','']
    else:lines+=['- 待验证：'+name,'']
lines+=['## 负载实验','','100 是混合业务迭代的目标到达速率，状态轮询请求另计。HTTP成功吞吐含轮询；P50/P95/P99为HTTP接口耗时。客户端端到端耗时含1秒轮询间隔，数据库端到端耗时使用 accepted_at 到 delivered_at 的同库时间。429为预期限流，单独统计。','','| 组别 | 目标迭代/s | 实际迭代/s | 成功HTTP/s | HTTP P50/P95/P99(ms) | 非预期错误率 | 丢弃迭代 | 失败/观察超时 | 已受理/最终送达 | DB投递P95(ms) |','|---|---:|---:|---:|---|---:|---:|---:|---:|---:|']
summary=[]
delivery_durations={}
for row in sql("SELECT mail_id,TIMESTAMPDIFF(MICROSECOND,accepted_at,delivered_at)/1000 FROM delivery_task WHERE status='DELIVERED';").splitlines():
    try:
        mail_id,duration=row.split('\t');delivery_durations[int(mail_id)]=float(duration)
    except (ValueError,TypeError):pass
for path in sorted((E/'load').glob('*/summary.json')):
    run_path=path.parent/'run.json'
    if not run_path.exists():continue
    run=read(run_path);metrics=read(path)['metrics'];ids=sorted(set(run.get('accepted',[])))
    state_path=path.parent/'accepted-final-states.json';states=read(state_path) if state_path.exists() else {}
    durations=[delivery_durations[mail_id] for mail_id in ids if mail_id in delivery_durations]
    (path.parent/'db-delivery-ms.json').write_text(json.dumps(durations),encoding='utf-8')
    d95=pct(durations,.95);h50=val(metrics,'http_req_duration','med');h95=val(metrics,'http_req_duration','p(95)');h99=val(metrics,'http_req_duration','p(99)')
    delivered=sum(v=='DELIVERED' for v in states.values());error=val(metrics,'unexpected_errors','rate')
    row={'label':run['label'],'duration':run['duration'],'http_p50':h50,'http_p95':h95,'http_p99':h99,'success_http_rate':val(metrics,'successful_requests','rate'),'iterations_rate':val(metrics,'iterations','rate'),'iterations_count':val(metrics,'iterations','count'),'unexpected_error_rate':error,'dropped_iterations':val(metrics,'dropped_iterations','count'),'observation_failures':val(metrics,'delivery_failures','count'),'accepted':len(ids),'delivered':delivered,'db_delivery_p95_ms':d95,'client_delivery_p95_ms':val(metrics,'delivery_e2e_ms','p(95)'),'submit_p95_ms':val(metrics,'submit_ms','p(95)'),'exit_code':run['exit_code']}
    summary.append(row)
    lines.append(f"| {row['label']} ({run['duration']}) | {run['rate']} | {row['iterations_rate']:.2f} | {row['success_http_rate']:.2f} | {h50:.1f}/{h95:.1f}/{h99:.1f} | {error:.3%} | {row['dropped_iterations']} | {row['observation_failures']} | {len(ids)}/{delivered} | {d95 if d95 is not None else '未取得'} |")
(E/'load-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
lines+=['','单/双实例总连接池均20，总消费者并发均4，总后端CPU配额均2，总后端内存均1536MiB。第一个 double-mixed-100-1 含重启后的冷启动/JIT影响，保留作冷启动实验，不作为稳定容量的单独证明。写入负载会增加测试历史记录，固定种子的一百万封基础邮件保持不变；总数据量增长是写入对照的局限，不能据此宣称吞吐翻倍。','', '## 交付与复现','','- 启动、迁移、压测、死信管理与故障步骤：HIGH_CONCURRENCY_RUNBOOK.md。','- 原始SQL与服务代码基线：evidence/baseline/。','- 原始压测数据、资源曲线采样、受理清单和逐封最终状态：evidence/load/。','- 页面截图：evidence/screenshots/；仅将成功且经预览确认的PNG作为证据。','', '## 判读原则','','达到100目标需要同时检查实际迭代吞吐、HTTP尾延迟、错误率、丢弃迭代、最终任务核对和积压恢复。短时试跑、入队速度、或成功HTTP吞吐中的状态轮询均不能代替业务吞吐。阈值未通过的轮次保留原始失败结果。未完成的正式专项矩阵必须单列，不能把30秒或1分钟诊断试跑写成10分钟三轮验证。']

lines+=['', '## 正式性能测量构建的对照与限制', '',
'正式性能测量构建 jar SHA256：6357dbac4a54e9518f78040bfd832de63fb3c31c57202a76da120bf9a87d647e。Nginx启用上游连接复用；应用与数据库使用Asia/Shanghai。混合压测临时放宽限流；正常运行恢复容量10、每分钟补充10。', '',
'每组均为10分钟三轮。预热不纳入正式结果。附件第一轮因用户暂停出现约2895秒原始时间戳断档，保留为受干扰诊断；正式附件组改用第2、第3和continuous补跑轮。', '',
'| 最终组 | HTTP P95三轮中位数(ms) | 客户端投递P95三轮中位数(ms) | 完成业务迭代合计 | 丢弃迭代三轮合计 | 非预期错误率最大值 | 受理/送达合计(含每轮setup邮件) | 通过阈值轮次 |',
'|---|---:|---:|---:|---:|---:|---:|---:|']
selected={
 '双实例100迭代/s':[f'double-release-mixed-100-{i}' for i in [1,2,3]],
 '单实例100迭代/s':[f'single-release-mixed-100-{i}' for i in [1,2,3]],
 '搜索1查询/s':[f'release-search-search-1-{i}' for i in [1,2,3]],
 '附件20下载/s':['release-attachment-attachment-20-2','release-attachment-attachment-20-3','release-attachment-continuous-attachment-20-1']}
bylabel={r['label']:r for r in summary}
for name,labels in selected.items():
    group=[bylabel[x] for x in labels if x in bylabel]
    if len(group)!=3:
        lines+=['| '+name+' | 待完成三轮 | | | | | |'];continue
    med=lambda field:statistics.median(r[field] for r in group)
    client_p95=f"{med('client_delivery_p95_ms'):.3f}" if '迭代' in name else '不适用'
    lines.append(f"| {name} | {med('http_p95'):.3f} | {client_p95} | {sum(r['iterations_count'] for r in group)} | {sum(r['dropped_iterations'] for r in group)} | {max(r['unexpected_error_rate'] for r in group):.3%} | {sum(r['accepted'] for r in group)}/{sum(r['delivered'] for r in group)} | {sum(r['exit_code']==0 for r in group)}/3 |")
lines+=['', '搜索和附件组的受理/送达仅为每轮setup验证邮件，并不是查询/下载数量；实际业务数见完成迭代列。', '', '最终交付构建 jar SHA256：57b7fb36ce198e37685449f3935aeec281a2b48afb011cd25ca8b43477c1e0b7。正式性能测量之后，修复了并发幂等重试在REPEATABLE READ旧快照下返回404的问题：在取得幂等键行锁之后，对既有任务使用FOR SHARE当前读。网关同时关闭上游被动摘除，并不再把应用503作为上游重试条件，避免业务拒绝扩散为no live upstreams。最终构建已进行针对性回归，但尚未重新完成整套正式性能矩阵，因此上面的性能数据属于明确列出的测量构建，不宣称是最终交付构建的完整性能验收。', '',
'100目标到达速率未在全部双实例正式轮次稳定通过，单实例三轮均有丢弃；这不是稳定容量承诺。丢弃发生在压测迭代调度阶段，须与已受理邮件的最终送达核对区分。模糊搜索三轮应按实际500ms阈值判读，不能用SQL执行计划改善代替HTTP阈值通过。', '',
'单实例存在投递积压、较高端到端延迟；Outbox发布与通知交接的吞吐需继续剖析和优化。双实例个别轮次有波动，不能据此宣称线性扩展。进一步调整发布批次、事务边界或并发之后，必须重新运行同构建、同资源正式对照。', '',
'每轮 endpoints.json 提供列表/详情/提交/状态轮询等分接口P50/P95/P99及HTTP状态计数；resource-summary.json 提供CPU均值/最大采样值、内存最大采样值；raw-continuity.json 核对负载时间连续性。CPU百分比以一个逻辑核100%计，内存为采样最大值，不代表瞬时绝对峰值。', '',
'报告中的旧失败、中断和不同构建结果均保留作诊断。缺失run.json的旧单实例实验未纳入汇总，原始数据仍在相应目录。截图及构建/回归日志见evidence目录，各自对应其记录的版本。']

lines+=['', '## 正式组资源采样汇总', '', '| 组 | 实例 | CPU均值三轮中位数(单核100%) | 内存采样峰值三轮中位数(MiB) |', '|---|---|---:|---:|']
for name,labels in selected.items():
    resources={}
    for label in labels:
        path=E/'load'/label/'resource-summary.json'
        if not path.exists():continue
        for instance,values in read(path).items():
            if 'backend' not in instance or not values.get('memory_max_mib'):continue
            resources.setdefault(instance,[]).append(values)
    for instance,values in sorted(resources.items()):
        lines.append(f"| {name} | {instance} | {statistics.median(v['cpu_mean_percent_one_core'] for v in values):.2f} | {statistics.median(v['memory_max_mib'] for v in values):.2f} |")

lines+=['', '## 最终回归与运行状态', '',
'最终构建Maven 15项测试无失败、前端构建通过。上下文测试存在本地数据库连接警告，不将其计为真实数据库集成证明。真实数据库/消息队列证据另见功能与故障JSON。', '',
'最终普通配置两轮集成回归通过（integration-final-confirmed.log、integration-final-repeat.log）：8并发原键重试一致、冲突409、其他用户状态404、接收人去重、用户跨实例共享10令牌且第11次429、无重复收件记录。故障与响应丢失重试另见faults-final.log、response-replay-final.log；快照404及网关502的原失败日志也保留。', '',
'最终运行审计：两个实例同一交付jar，故障注入关闭，限流容量10/每分钟补充10，测试端口已移除。delivery_task共84023条均DELIVERED，未发布Outbox、重复接收记录、待学习任务、残留故障计数均为0，见final-runtime.json；这是本轮测试库的最终截面，不是生产承诺。', '',
'最终页面截图：evidence/screenshots/million-inbox-final-build-001.png，已预览确认100001封收件箱计数、当前页20封及加载更多。本轮停滞截图进程已停止，其空文件/锁作为无用临时产物清理，失败日志保留。', '',
'未完成项：最终幂等/网关修复构建的全套正式性能矩阵尚未重跑；100迭代/s稳定性、单实例投递积压、模糊搜索500ms阈值仍未达标。不得据此报告整体性能验收完成。']
(ROOT/'docs/HIGH_CONCURRENCY_REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Wrote docs/HIGH_CONCURRENCY_REPORT.md')
