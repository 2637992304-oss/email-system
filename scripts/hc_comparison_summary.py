"""Summarize a single comparison label from its retained k6 and reconciliation artifacts."""
import argparse
import json
import re
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'docs/evidence'
GROUPS = [('single', 'mixed', 100), ('double', 'mixed', 100),
          ('search', 'search', 1), ('attachment', 'attachment', 20)]


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def load_round(directory, rate, expected_instances):
    run_path = directory / 'run.json'
    if not run_path.exists():
        return None
    run = read(run_path)
    metrics = read(directory / 'summary.json')['metrics']
    states = read(directory / 'accepted-final-states.json')
    continuity_path = directory / 'raw-continuity.json'
    continuity = read(continuity_path) if continuity_path.exists() else {}
    def metric(name, key, default=None):
        return metrics.get(name, {}).get('values', {}).get(key, default)

    rows = (directory / 'audit.txt').read_text(encoding='utf-8').splitlines()
    duplicate_rows = [row for row in rows if len(row.split('\t')) == 3]
    accepted = len(set(run['accepted']))
    delivered = sum(status == 'DELIVERED' for status in states.values())
    reconciled = set(states) == {str(mail) for mail in run['accepted']} and delivered == accepted and not duplicate_rows
    topology_path = directory / 'topology.json'
    topology = read(topology_path) if topology_path.exists() else {}
    active = [instance for instance in topology.values() if instance['running']]
    budget_matches = (
        len(active) == expected_instances
        and all(node.get('jar_sha256') == run.get('backend_jar_sha256') for node in active)
        and sum(node['cpu_nanocores'] for node in active) == 2_000_000_000
        and sum(node['memory_bytes'] for node in active) == 1_610_612_736
        and sum(node['db_pool'] for node in active) == 20
        and sum(node['consumers'] for node in active) == 4
    )
    iterations = metric('iterations', 'count', 0)
    drops = metric('dropped_iterations', 'count', 0)
    unavailable = metric('expected_429', 'count', 0)
    samples = []
    resources = directory / 'resources.jsonl'
    if resources.exists():
        for line in resources.read_text(encoding='utf-8').splitlines():
            rows = json.loads(line)['db'].splitlines()
            if not rows or not rows[-1].isdigit():
                continue
            outstanding = sum(int(row.split('\t')[1]) for row in rows
                              if row.startswith(('PENDING\t', 'PROCESSING\t')))
            samples.append((outstanding, int(rows[-1])))
    return {
        'max_sampled_outstanding_tasks': max((x[0] for x in samples), default=None),
        'max_sampled_unpublished_outbox': max((x[1] for x in samples), default=None),
        'label': run['label'], 'build': run.get('backend_jar_sha256'),
        'elapsed_seconds': run['elapsed_seconds'], 'iterations': iterations,
        'business_rate_per_second': iterations / 600,
        'http_p50_ms': metric('http_req_duration', 'med'),
        'http_p95_ms': metric('http_req_duration', 'p(95)'),
        'http_p99_ms': metric('http_req_duration', 'p(99)'),
        'submit_p95_ms': metric('submit_ms', 'p(95)'),
        'observed_delivery_p95_ms': metric('delivery_e2e_ms', 'p(95)'),
        'unexpected_errors': metric('unexpected_errors', 'rate', 1),
        'dropped': drops, 'http_429': unavailable,
        'accepted': accepted, 'delivered': delivered, 'reconciled': reconciled, 'budget_matches': budget_matches,
        'max_raw_gap_seconds': continuity.get('largest_load_timestamp_gap_seconds'),
        'passed': (
            run['duration'] == '10m' and run['rate'] == rate
            and run['exit_code'] == 0 and reconciled and budget_matches and drops == 0 and unavailable == 0
            and abs(iterations - rate * 600) <= 1
            and bool(continuity) and not continuity.get('requires_continuity_review', True)
        )
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('label')
    parser.add_argument('--update-report', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch('[A-Za-z0-9_-]+', args.label):
        parser.error('Invalid comparison label')
    groups = {}
    for group, mode, rate in GROUPS:
        paths = [
            EVIDENCE / 'load' / f'{args.label}-{group}-{mode}-{rate}-{round_number}'
            for round_number in range(1, 4)
        ]
        groups[group] = [r for path in paths if (r := load_round(path, rate, 1 if group == 'single' else 2)) is not None]

    build_ids = {r['build'] for rounds in groups.values() for r in rounds}
    same_build = len(build_ids) == 1 and None not in build_ids
    output = {'label': args.label, 'same_build': same_build,
              'builds': sorted(build or 'unknown' for build in build_ids), 'groups': groups}
    (EVIDENCE / f'{args.label}-matrix-summary.json').write_text(
        json.dumps(output, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    for name, rounds in groups.items():
        p95 = [r['http_p95_ms'] for r in rounds if r['http_p95_ms'] is not None]
        print(f'{name}: {sum(r["passed"] for r in rounds)}/{len(rounds)} passed, '
              f'HTTP P95 median={statistics.median(p95) if p95 else None}')
    print(f'Same build: {same_build}')
    if args.update_report:
        update_report(args.label, groups, same_build, output['builds'])



def update_report(label, groups, same_build, builds):
    heading = '## 2026-11-19 调优复测'
    report = ROOT / 'docs/HIGH_CONCURRENCY_REPORT.md'
    previous = report.read_text(encoding='utf-8').split(heading, 1)[0].rstrip()
    lines = [
        heading, '',
        '本节记录本轮调优和同构建复测，上文保留历史构建的结果。',
        '',
        '- Outbox 批量认领和确认采用租约与发布确认，调度任务使用独立线程。投递写入按批执行，通知收件人不再分两次查询。',
        '- 第一版复测中，未发布 Outbox 接近零时投递仍积压。MySQL 指纹将剩余瓶颈定位到发件人垃圾历史查询。V007 回填 87,084 条记录并对账无差异，覆盖索引单次执行约 29 ms，同库旧连接方式约 124 ms。原有识别规则和阈值继续适用。见 evidence/tuning-spam-digest-before.tsv 和 evidence/tuning-spam-migration.log。',
        '- V006 回填 1,419,267 条收件记录的原始发件时间，对账无差异。搜索用有序索引分别读取发件和收件候选，保留关键词子串、可见权限和游标语义。复测计划在收件索引读取 50 行后得到 21 条候选，SQL 约 9.6 ms。稀有关键词仍可能检查更多记录。见 evidence/search-ordered-plan.json。',
        '- 带垃圾历史索引的候选构建仍在单实例正式首轮遭遇瞬时拥堵，丢弃 731 次，HTTP P95 约 830 ms，受理的 5,928 个任务均送达。采样显示准入锁等待与垃圾警告逐收件人写入负担较大。最终构建将容量锁移到受理事务末段，读取最新待处理量，并合并垃圾警告写入。旧构建失败轮和后续中止轮保留在 evidence/load/tuned-final-*。',
        '- 带垃圾历史索引的候选构建单实例三分钟试跑 18,001 次迭代无丢弃、1,801 个受理任务全部送达，HTTP P95 约 31.4 ms，客户端投递观测 P95 约 1.09 秒。正式结果见下表。',
        '- 15 项 Maven 测试通过。真实环境通过搜索跨页、权限与去重，跨实例 SSE 和附件，重试幂等、模型版本、租约、死信重放与默认共享限流回归。实例退出时曾出现读超时，已调整网关并复测通过，原失败日志仍保留。',
        '',
        '单、双实例均限制为总计 2 CPU、1536 MiB、20 个数据库连接和 4 个投递消费者。固定种子数据保留，各轮写入会增加后续记录。搜索 1 次/秒，附件下载 20 次/秒，混合负载 100 次业务迭代/秒。正常运行恢复每分钟 10 个令牌。', '',
        f'正式记录中的后端构建 SHA256：{", ".join(builds) or "暂无"}。同构建核对：{"通过" if same_build else "未通过"}。', '',
        '| 组 | 轮次 | 业务迭代 | HTTP P50/P95/P99 (ms) | 非预期错误 | 丢弃 | 受理/送达 | 任务/Outbox 采样峰值 | 投递 P95 (ms) | 通过 |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---|'
    ]
    for name, rounds in groups.items():
        for number, row in enumerate(rounds, 1):
            def fmt(value):
                return f'{value:.2f}' if value is not None else '—'
            timings = '/'.join(fmt(row[key]) for key in ('http_p50_ms', 'http_p95_ms', 'http_p99_ms'))
            peaks = f"{row['max_sampled_outstanding_tasks']}/{row['max_sampled_unpublished_outbox']}"
            lines.append(
                f"| {name} | {number} | {row['iterations']} | {timings} | {row['unexpected_errors']:.3%} | "
                f"{row['dropped']} | {row['accepted']}/{row['delivered']} | {peaks} | "
                f"{fmt(row['observed_delivery_p95_ms'])} | {'是' if row['passed'] else '否'} |"
            )
    lines += ['', '搜索和附件组的受理任务为初始化验证邮件。混合负载的投递 P95 含客户端轮询间隔。原始分接口指标、时间连续性、资源配额和受理对账保存在 evidence/load/ 各轮目录。', '',
              '| 组 | 通过/计划 | HTTP P95 三轮中位数 (ms) |', '|---|---:|---:|']
    for name, rounds in groups.items():
        p95 = [row['http_p95_ms'] for row in rounds if row['http_p95_ms'] is not None]
        median = f'{statistics.median(p95):.2f}' if len(p95) == 3 else '待齐'
        lines.append(f'| {name} | {sum(row["passed"] for row in rounds)}/3 | {median} |')
    all_passed = same_build and all(len(rounds) == 3 and all(r['passed'] for r in rounds)
                                    for rounds in groups.values())
    lines += ['', f'正式性能矩阵{"通过" if all_passed else "存在未完成或未达标轮次"}。机器可读汇总见 evidence/{label}-matrix-summary.json。']
    report.write_text(previous + '\n\n' + '\n'.join(lines) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
