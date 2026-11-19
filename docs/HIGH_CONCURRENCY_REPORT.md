# 高并发改造实测报告

2026-11-19 本地调优复测已完成。最终交付构建的 12 个正式轮次全部通过，见[调优复测](#2026-11-19-调优复测)。前面的内容保留为历史证据。

本轮实现及验证结果见下方最终构建汇总。可靠性验证与性能阈值分别判读，存在未达标轮次，不宣称全部验收通过。历史测试属于各自记录的构建，不能混作最终版本的正式数据。

所有数据来自本地 Docker Compose。仓库中的 evidence/ 包含验收汇总、关键查询计划、功能回归和正式轮次的精简指标。全文提及的原始 k6 数据、完整运行日志与历史诊断保存在执行测试的本地工作区，可按运行说明复测。

## 环境与固定数据

### host-hardware.json

```json
{
  "cpu": {
    "Name": "AMD Ryzen 5 4500U with Radeon Graphics         ",
    "NumberOfCores": 6,
    "NumberOfLogicalProcessors": 6
  },
  "memoryBytes": 16505954304
}
```

### environment.json

```json
{
  "host_os": "Windows-11-10.0.26200-SP0",
  "python": "3.13.9",
  "database": "8.0.46",
  "docker_version": "29.8.2",
  "docker_resources": "CPU=6 Memory=7996551168",
  "container_limits": "/email-hc-backend-1 Running=true CPU=2000000000 Memory=1610612736\n/email-hc-backend-b-1 Running=false CPU=1000000000 Memory=805306368\n/email-hc-db-1 Running=true CPU=2000000000 Memory=1073741824",
  "nginx_version": "nginx version: nginx/1.28.3",
  "redis_version": "Redis server v=7.4.11 sha=00000000:0 malloc=jemalloc-5.3.0 bits=64 build=40ff01a501d8e4b6",
  "rabbitmq_version": "3.13.7",
  "java_version": "openjdk version \"21.0.13\" 2026-10-20 LTS\nOpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)\nOpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing)",
  "k6_version": "k6 v0.54.0 (commit/baba871c8a, go1.23.1, linux/amd64)"
}
```

### backend-build.json

```json
{
  "Hash": "6C03DCA9563167F98193F8B3CDCEA5CD9498333B0495464C01F727C7B662C20C"
}
```

### seed-100000.json

```json
{
  "seed": 42,
  "requested_count": 100000,
  "result": "100000\n100000\nmail_system.mail\tanalyze\tstatus\tOK\nmail_system.mail_receiver\tanalyze\tstatus\tOK",
  "elapsed_seconds": 7.436
}
```

### seed-1000000.json

```json
{
  "seed": 42,
  "requested_count": 1000000,
  "result": "1000000\n100000\nmail_system.mail\tanalyze\tstatus\tOK\nmail_system.mail_receiver\tanalyze\tstatus\tOK",
  "elapsed_seconds": 66.014
}
```

## SQL 对照

SQL时间来自实际 EXPLAIN ANALYZE，不等同于 API 响应时间。早期 original_inbox 使用等价简化选择列；exact-original 组从保存的原始 Mapper 读取完整原 SQL。

| 数据/版本 | 查询 | 三次根节点完成时间中位数(ms) |
|---|---|---:|
| query-100k-after-index | original_inbox | 518.000 |
| query-100k-after-index | paged_inbox | 628.000 |
| query-100k-after-index | paged_sent | 0.100 |
| query-100k-before-index | original_inbox | 547.000 |
| query-100k-before-index | paged_inbox | 658.000 |
| query-100k-before-index | paged_sent | 0.065 |
| query-1m-after-index | original_inbox | 498.000 |
| query-1m-after-index | paged_inbox | 555.000 |
| query-1m-after-index | paged_sent | 0.076 |
| query-1m-final-order | original_inbox | 481.000 |
| query-1m-final-order | paged_inbox | 2.560 |
| query-1m-final-order | paged_sent | 0.076 |

索引选择经历了真实验证：仅增加分页和索引时仍发生全量排序；改为按接收记录的 receive_time、mail_id 排序后，收件箱查询使用 idx_receiver_cursor，只读取当前页所需记录。已发送列表由每封两次补查改为当前页一次批量收件人查询。

## 正确性与故障实验

### integration.json

```json
{
  "timestamp": "2026-11-18T23:12:03+0800",
  "accepted": [
    2000083994,
    2000084002,
    2000083999,
    2000083998,
    2000084001,
    2000083996,
    2000083995,
    2000084004,
    2000083997,
    2000084003,
    2000084000
  ],
  "concurrent_replays": 8,
  "same_key_conflict": 409,
  "foreign_status": 404,
  "deduplicated_receivers": 1,
  "rate_statuses": [
    202,
    202,
    202,
    202,
    202,
    202,
    202,
    202,
    202,
    429,
    202
  ],
  "backends": [
    "172.20.0.2:8080",
    "172.20.0.5:8080"
  ],
  "duplicates": 0,
  "duration_seconds": 2.934
}
```

### functional.json

```json
{
  "unique_inbox_mails": 21,
  "star_flags_preserved": 21,
  "priority_pages": [
    20,
    1
  ],
  "priority_read_flag_preserved": true,
  "filtered_read_count": 1,
  "future_date_filter_count": 0,
  "search_metadata_complete": true,
  "soft_delete_restore": true,
  "spam_delivered_to_spam_box": 2000048842,
  "receiver_limit_status": 400
}
```

### response-replay.json

```json
{
  "original_response": "dropped_after_upstream_202",
  "idempotency_key": "703181c2bfc84e209365569f78310914",
  "mail": 2000083982,
  "retry_mail_ids_equal": true,
  "backend_count": 2,
  "receiver_rows": 1,
  "status": "DELIVERED"
}
```

### cross-instance.json

```json
{
  "sse_connection": "A",
  "submission_and_consumer": "B",
  "notified_mail": 2000048845,
  "attachment_uploaded_at": "A",
  "attachment_downloaded_at": "B",
  "sha256": "200c174ef4c5376ace48b29287299b72d20bf97200556222e79785079a37ce25",
  "bound_attachment_reuse_status": 400,
  "post_send_upload_status": 400,
  "old_connection_closed_new_connection_received": true
}
```

### models.json

```json
{
  "mail": 2000048844,
  "previous_version": 2,
  "committed_version": 3,
  "instance_versions": [
    3,
    3
  ],
  "snapshot_sha256": [
    "389c926395c1a1e2e52ceed6fccf90cb9f70be1dfee3b776228164515d715d9b",
    "389c926395c1a1e2e52ceed6fccf90cb9f70be1dfee3b776228164515d715d9b"
  ],
  "pending_learning_jobs": 0
}
```

### faults.json

```json
{
  "broker_outage_and_backend_restart": {
    "mail": 2000083977,
    "status": "DELIVERED",
    "client_observed_recovery_seconds": 26.55
  },
  "redis_outage": {
    "new_submission": 503,
    "read": 200
  },
  "publish_confirmed": {
    "mail": 2000083978,
    "status": "DELIVERED",
    "receiver_rows": 1
  },
  "consumer_committed": {
    "mail": 2000083979,
    "status": "DELIVERED",
    "receiver_rows": 1
  },
  "processing_claimed": {
    "mail": 2000083980,
    "status": "DELIVERED",
    "receiver_rows": 1
  },
  "dead_letter_replay": {
    "mail": 2000083981,
    "attempts_before_replay": 4,
    "status": "DELIVERED"
  },
  "single_backend_exit": "readable"
}
```

### backlog.json

```json
{
  "accepted": [
    2000083894,
    2000083895,
    2000083896,
    2000083897,
    2000083898,
    2000083899,
    2000083900,
    2000083901,
    2000083902,
    2000083903,
    2000083904,
    2000083905,
    2000083906,
    2000083907,
    2000083908,
    2000083909,
    2000083910,
    2000083911,
    2000083912,
    2000083913,
    2000083914,
    2000083915,
    2000083916,
    2000083917,
    2000083918,
    2000083919,
    2000083920,
    2000083921,
    2000083922,
    2000083923,
    2000083924,
    2000083925,
    2000083926,
    2000083927,
    2000083928,
    2000083929,
    2000083930,
    2000083931,
    2000083932,
    2000083933,
    2000083934,
    2000083935,
    2000083936,
    2000083937,
    2000083938,
    2000083939,
    2000083940,
    2000083941,
    2000083942,
    2000083943
  ],
  "backlog_while_broker_stopped": 50,
  "client_observed_restart_to_all_delivered_seconds": 63.291,
  "delivered": 50
}
```

### sql-count.json

```json
{
  "endpoint": "/api/mail/sent?size=20",
  "returned": 20,
  "list_related_selects": 3,
  "sql": [
    "SELECT             user_id,             email,             username,             password,             register_time,             status         FROM `user`         WHERE user_id = 1000000000",
    "SELECT EXISTS(SELECT 1 FROM mail_star ms WHERE ms.user_id=1000000000 AND ms.mail_id=m.mail_id) is_starred,m.delivery_status,m.mail_id,m.sender_id,u.username sender_name,u.email sender_email,m.subject,         LEFT(m.content,50) preview,m.has_attachment,m.send_time,m.priority,m.is_spam,          NULL is_read,NULL receive_time        FROM mail m JOIN user u ON u.user_id=m.sender_id               WHERE m.mail_status=1        AND m.sender_id=1000000000 AND m.sender_deleted=FALSE                            ORDER BY m.send_time DESC,m.mail_id DESC         LIMIT 21",
    "SELECT mr.mail_id,u.username,u.email FROM mail_receiver mr JOIN user u ON u.user_id=mr.receiver_id       WHERE mr.receiver_type IN ('TO','CC') AND mr.mail_id IN        (  2000083944 , 2000083943 , 2000083942 , 2000083941 , 2000083940 , 2000083939 , 2000083938 , 2000083937 , 2000083936 , 2000083935 , 2000083934 , 2000083933 , 2000083932 , 2000083931 , 2000083930 , 2000083929 , 2000083928 , 2000083927 , 2000083926 , 2000083925 )        ORDER BY mr.mail_id,FIELD(mr.receiver_type,'TO','CC'),u.email"
  ],
  "baseline_formula": "2+2N SELECTs from original service (includes user lookup); source snapshot retained"
}
```

### final-runtime.json

```json
{
  "containers": [
    {
      "name": "/email-hc-backend-1",
      "running": true,
      "jar_sha256": "57b7fb36ce198e37685449f3935aeec281a2b48afb011cd25ca8b43477c1e0b7",
      "cpu_nano": 1000000000,
      "memory": 805306368,
      "environment": {
        "RATE_PER_MINUTE": "10",
        "MAIL_TEST_FAULT_ENABLED": "false",
        "CONSUMERS": "2",
        "DB_POOL_SIZE": "10",
        "TZ": "Asia/Shanghai",
        "RATE_CAPACITY": "10"
      },
      "ports": {}
    },
    {
      "name": "/email-hc-backend-b-1",
      "running": true,
      "jar_sha256": "57b7fb36ce198e37685449f3935aeec281a2b48afb011cd25ca8b43477c1e0b7",
      "cpu_nano": 1000000000,
      "memory": 805306368,
      "environment": {
        "RATE_PER_MINUTE": "10",
        "MAIL_TEST_FAULT_ENABLED": "false",
        "CONSUMERS": "2",
        "DB_POOL_SIZE": "10",
        "TZ": "Asia/Shanghai",
        "RATE_CAPACITY": "10"
      },
      "ports": {}
    }
  ],
  "audit": "DELIVERED\t84023\n0\n0\n0\n0"
}
```

## 负载实验

100 是混合业务迭代的目标到达速率，状态轮询请求另计。HTTP成功吞吐含轮询；P50/P95/P99为HTTP接口耗时。客户端端到端耗时含1秒轮询间隔，数据库端到端耗时使用 accepted_at 到 delivered_at 的同库时间。429为预期限流，单独统计。

| 组别 | 目标迭代/s | 实际迭代/s | 成功HTTP/s | HTTP P50/P95/P99(ms) | 非预期错误率 | 丢弃迭代 | 失败/观察超时 | 已受理/最终送达 | DB投递P95(ms) |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|
| double-batched-mixed-100-1 (10m) | 100 | 99.83 | 110.24 | 7.0/38.0/63.8 | 0.000% | 0 | 0 | 6001/6001 | 896.4 |
| double-batched-mixed-100-2 (10m) | 100 | 99.84 | 109.95 | 7.1/36.0/68.2 | 0.000% | 0 | 0 | 6001/6001 | 710.688 |
| double-batched-mixed-100-3 (10m) | 100 | 99.83 | 110.69 | 7.0/34.9/65.7 | 0.000% | 0 | 0 | 6001/6001 | 1256.558 |
| double-batched-warmup-mixed-100-1 (3m) | 100 | 90.20 | 358.84 | 32.2/1095.7/2435.8 | 0.042% | 746 | 0 | 1726/1726 | 50828.900499999996 |
| double-final-mixed-100-1 (10m) | 100 | 92.29 | 353.10 | 5.9/61.3/255.3 | 0.000% | 1676 | 0 | 5833/5833 | 60144.109599999996 |
| double-final-mixed-100-2 (stopped) | 100 | 99.42 | 215.97 | 4.9/29.1/50.2 | 0.000% | 55 | 0 | 4081/4081 | 18446.031 |
| double-mixed-100-1 (10m) | 100 | 90.13 | 197.46 | 6.3/70.6/731.6 | 0.025% | 4495 | 0 | 5551/5551 | 18255.480499999998 |
| double-mixed-100-2 (stopped) | 100 | 92.18 | 216.58 | 4.9/31.6/61.2 | 0.000% | 3282 | 0 | 4067/4067 | 17581.9947 |
| double-pilot-mixed-100-1 (30s) | 100 | 90.86 | 111.50 | 11.7/126.9/477.9 | 0.000% | 3 | 0 | 300/300 | 2789.6493 |
| double-pilot-mixed-20-1 (30s) | 20 | 19.17 | 21.09 | 14.7/46.4/166.0 | 0.000% | 0 | 0 | 61/61 | 640.287 |
| double-pilot-mixed-50-1 (30s) | 50 | 48.33 | 53.49 | 11.3/44.4/81.8 | 0.000% | 0 | 0 | 151/151 | 1076.2224999999999 |
| double-release-mixed-100-1 (10m) | 100 | 99.83 | 109.84 | 6.6/36.2/58.3 | 0.000% | 0 | 0 | 6001/6001 | 583.371 |
| double-release-mixed-100-2 (10m) | 100 | 99.83 | 110.03 | 7.0/37.8/61.4 | 0.000% | 0 | 0 | 6001/6001 | 763.555 |
| double-release-mixed-100-3 (10m) | 100 | 99.32 | 237.08 | 6.9/192.0/1179.8 | 0.000% | 309 | 0 | 5970/5970 | 32709.51195 |
| double-release-warm-mixed-100-1 (3m) | 100 | 99.00 | 135.62 | 13.3/355.5/1815.2 | 0.037% | 33 | 0 | 1797/1797 | 13587.746599999999 |
| double-warmup-mixed-100-1 (3m) | 100 | 94.66 | 120.90 | 7.3/28.8/58.3 | 0.000% | 0 | 0 | 1801/1801 | 9508.598 |
| release-attachment-attachment-20-1 (10m) | 20 | 3.43 | 3.43 | 5.5/8.8/13.0 | 0.000% | 0 | 0 | 1/1 | 380.228 |
| release-attachment-attachment-20-2 (10m) | 20 | 19.97 | 19.97 | 6.1/9.3/11.8 | 0.000% | 0 | 0 | 1/1 | 414.112 |
| release-attachment-attachment-20-3 (10m) | 20 | 19.97 | 19.97 | 5.5/8.7/11.8 | 0.000% | 0 | 0 | 1/1 | 329.888 |
| release-attachment-continuous-attachment-20-1 (10m) | 20 | 19.95 | 19.95 | 4.5/7.5/9.9 | 0.000% | 0 | 0 | 1/1 | 604.003 |
| release-search-search-1-1 (10m) | 1 | 1.00 | 1.00 | 620.9/709.8/890.2 | 0.000% | 0 | 0 | 1/1 | 1759.595 |
| release-search-search-1-2 (10m) | 1 | 1.00 | 1.00 | 640.9/881.8/1210.7 | 0.000% | 0 | 0 | 1/1 | 325.441 |
| release-search-search-1-3 (10m) | 1 | 1.00 | 1.00 | 635.5/866.7/1010.0 | 0.000% | 0 | 0 | 1/1 | 623.628 |
| single-batched-warmup-mixed-100-1 (3m) | 100 | 98.23 | 117.03 | 7.1/59.2/260.4 | 0.000% | 0 | 0 | 1801/1801 | 3793.115 |
| single-release-mixed-100-1 (10m) | 100 | 65.11 | 483.64 | 7.5/58.6/128.7 | 0.000% | 17000 | 0 | 4301/4301 | 85930.219 |
| single-release-mixed-100-2 (10m) | 100 | 74.35 | 474.05 | 5.0/44.0/89.7 | 0.000% | 10991 | 0 | 4902/4902 | 75451.17910000001 |
| single-release-mixed-100-3 (10m) | 100 | 64.76 | 478.67 | 7.0/68.1/256.1 | 0.000% | 17373 | 0 | 4263/4263 | 84709.12449999998 |
| single-release-warm-mixed-100-1 (3m) | 100 | 94.55 | 136.66 | 8.2/197.8/993.5 | 0.000% | 3 | 0 | 1800/1800 | 9640.97215 |

单/双实例总连接池均20，总消费者并发均4，总后端CPU配额均2，总后端内存均1536MiB。第一个 double-mixed-100-1 含重启后的冷启动/JIT影响，保留作冷启动实验，不作为稳定容量的单独证明。写入负载会增加测试历史记录，固定种子的一百万封基础邮件保持不变；总数据量增长是写入对照的局限，不能据此宣称吞吐翻倍。

## 交付与复现

- 启动、迁移、压测、死信管理与故障步骤：HIGH_CONCURRENCY_RUNBOOK.md。
- 原始SQL与服务代码基线：evidence/baseline/。
- 原始压测数据、资源曲线采样、受理清单和逐封最终状态：evidence/load/。
- 页面截图：evidence/screenshots/；仅将成功且经预览确认的PNG作为证据。

## 判读原则

达到100目标需要同时检查实际迭代吞吐、HTTP尾延迟、错误率、丢弃迭代、最终任务核对和积压恢复。短时试跑、入队速度、或成功HTTP吞吐中的状态轮询均不能代替业务吞吐。阈值未通过的轮次保留原始失败结果。未完成的正式专项矩阵必须单列，不能把30秒或1分钟诊断试跑写成10分钟三轮验证。

## 正式性能测量构建的对照与限制

正式性能测量构建 jar SHA256：6357dbac4a54e9518f78040bfd832de63fb3c31c57202a76da120bf9a87d647e。Nginx启用上游连接复用；应用与数据库使用Asia/Shanghai。混合压测临时放宽限流；正常运行恢复容量10、每分钟补充10。

每组均为10分钟三轮。预热不纳入正式结果。附件第一轮因用户暂停出现约2895秒原始时间戳断档，保留为受干扰诊断；正式附件组改用第2、第3和continuous补跑轮。

| 最终组 | HTTP P95三轮中位数(ms) | 客户端投递P95三轮中位数(ms) | 完成业务迭代合计 | 丢弃迭代三轮合计 | 非预期错误率最大值 | 受理/送达合计(含每轮setup邮件) | 通过阈值轮次 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 双实例100迭代/s | 37.844 | 1096.000 | 179692 | 309 | 0.000% | 17972/17972 | 2/3 |
| 单实例100迭代/s | 58.575 | 85612.950 | 134638 | 45364 | 0.000% | 13466/13466 | 0/3 |
| 搜索1查询/s | 866.668 | 不适用 | 1801 | 0 | 0.000% | 3/3 | 0/3 |
| 附件20下载/s | 8.665 | 不适用 | 36001 | 0 | 0.000% | 3/3 | 3/3 |

搜索和附件组的受理/送达仅为每轮setup验证邮件，并不是查询/下载数量；实际业务数见完成迭代列。

最终交付构建 jar SHA256：57b7fb36ce198e37685449f3935aeec281a2b48afb011cd25ca8b43477c1e0b7。正式性能测量之后，修复了并发幂等重试在REPEATABLE READ旧快照下返回404的问题：在取得幂等键行锁之后，对既有任务使用FOR SHARE当前读。网关同时关闭上游被动摘除，并不再把应用503作为上游重试条件，避免业务拒绝扩散为no live upstreams。最终构建已进行针对性回归，但尚未重新完成整套正式性能矩阵，因此上面的性能数据属于明确列出的测量构建，不宣称是最终交付构建的完整性能验收。

100目标到达速率未在全部双实例正式轮次稳定通过，单实例三轮均有丢弃；这不是稳定容量承诺。丢弃发生在压测迭代调度阶段，须与已受理邮件的最终送达核对区分。模糊搜索三轮应按实际500ms阈值判读，不能用SQL执行计划改善代替HTTP阈值通过。

单实例存在投递积压、较高端到端延迟；Outbox发布与通知交接的吞吐需继续剖析和优化。双实例个别轮次有波动，不能据此宣称线性扩展。进一步调整发布批次、事务边界或并发之后，必须重新运行同构建、同资源正式对照。

每轮 endpoints.json 提供列表/详情/提交/状态轮询等分接口P50/P95/P99及HTTP状态计数；resource-summary.json 提供CPU均值/最大采样值、内存最大采样值；raw-continuity.json 核对负载时间连续性。CPU百分比以一个逻辑核100%计，内存为采样最大值，不代表瞬时绝对峰值。

报告中的旧失败、中断和不同构建结果均保留作诊断。缺失run.json的旧单实例实验未纳入汇总，原始数据仍在相应目录。截图及构建/回归日志见evidence目录，各自对应其记录的版本。

## 正式组资源采样汇总

| 组 | 实例 | CPU均值三轮中位数(单核100%) | 内存采样峰值三轮中位数(MiB) |
|---|---|---:|---:|
| 双实例100迭代/s | email-hc-backend-1 | 23.19 | 311.40 |
| 双实例100迭代/s | email-hc-backend-b-1 | 24.20 | 303.30 |
| 单实例100迭代/s | email-hc-backend-1 | 79.17 | 324.80 |
| 搜索1查询/s | email-hc-backend-1 | 1.42 | 244.60 |
| 搜索1查询/s | email-hc-backend-b-1 | 2.42 | 245.20 |
| 附件20下载/s | email-hc-backend-1 | 4.76 | 258.60 |
| 附件20下载/s | email-hc-backend-b-1 | 5.14 | 262.50 |

## 最终回归与运行状态

最终构建Maven 15项测试无失败、前端构建通过。上下文测试存在本地数据库连接警告，不将其计为真实数据库集成证明。真实数据库/消息队列证据另见功能与故障JSON。

最终普通配置两轮集成回归通过（integration-final-confirmed.log、integration-final-repeat.log）：8并发原键重试一致、冲突409、其他用户状态404、接收人去重、用户跨实例共享10令牌且第11次429、无重复收件记录。故障与响应丢失重试另见faults-final.log、response-replay-final.log；快照404及网关502的原失败日志也保留。

最终运行审计：两个实例同一交付jar，故障注入关闭，限流容量10/每分钟补充10，测试端口已移除。delivery_task共84023条均DELIVERED，未发布Outbox、重复接收记录、待学习任务、残留故障计数均为0，见final-runtime.json；这是本轮测试库的最终截面，不是生产承诺。

最终页面截图：evidence/screenshots/million-inbox-final-build-001.png，已预览确认100001封收件箱计数、当前页20封及加载更多。本轮停滞截图进程已停止，其空文件/锁作为无用临时产物清理，失败日志保留。

未完成项：最终幂等/网关修复构建的全套正式性能矩阵尚未重跑；100迭代/s稳定性、单实例投递积压、模糊搜索500ms阈值仍未达标。不得据此报告整体性能验收完成。

## 2026-11-19 调优复测

本节记录本轮调优和同构建复测，上文保留历史构建的结果。

- Outbox 批量认领和确认采用租约与发布确认，调度任务使用独立线程。投递写入按批执行，通知收件人不再分两次查询。
- 第一版复测中，未发布 Outbox 接近零时投递仍积压。MySQL 指纹将剩余瓶颈定位到发件人垃圾历史查询。V007 回填 87,084 条记录并对账无差异，覆盖索引单次执行约 29 ms，同库旧连接方式约 124 ms。原有识别规则和阈值继续适用。见 evidence/tuning-spam-digest-before.tsv 和 evidence/tuning-spam-migration.log。
- V006 回填 1,419,267 条收件记录的原始发件时间，对账无差异。搜索用有序索引分别读取发件和收件候选，保留关键词子串、可见权限和游标语义。复测计划在收件索引读取 50 行后得到 21 条候选，SQL 约 9.6 ms。稀有关键词仍可能检查更多记录。见 evidence/search-ordered-plan.json。
- 带垃圾历史索引的候选构建仍在单实例正式首轮遭遇瞬时拥堵，丢弃 731 次，HTTP P95 约 830 ms，受理的 5,928 个任务均送达。采样显示准入锁等待与垃圾警告逐收件人写入负担较大。最终构建将容量锁移到受理事务末段，读取最新待处理量，并合并垃圾警告写入。旧构建失败轮和后续中止轮保留在 evidence/load/tuned-final-*。
- 带垃圾历史索引的候选构建单实例三分钟试跑 18,001 次迭代无丢弃、1,801 个受理任务全部送达，HTTP P95 约 31.4 ms，客户端投递观测 P95 约 1.09 秒。正式结果见下表。
- 15 项 Maven 测试通过。真实环境通过搜索跨页、权限与去重，跨实例 SSE 和附件，重试幂等、模型版本、租约、死信重放与默认共享限流回归。实例退出时曾出现读超时，已调整网关并复测通过，原失败日志仍保留。

单、双实例均限制为总计 2 CPU、1536 MiB、20 个数据库连接和 4 个投递消费者。固定种子数据保留，各轮写入会增加后续记录。搜索 1 次/秒，附件下载 20 次/秒，混合负载 100 次业务迭代/秒。正常运行恢复每分钟 10 个令牌。

正式记录中的后端构建 SHA256：77d1cb00891defe8a58dc7adffad7539488432f29e32f3f51751084603fcac4a。同构建核对：通过。

| 组 | 轮次 | 业务迭代 | HTTP P50/P95/P99 (ms) | 非预期错误 | 丢弃 | 受理/送达 | 任务/Outbox 采样峰值 | 投递 P95 (ms) | 通过 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| single | 1 | 60001 | 7.81/36.13/55.12 | 0.000% | 0 | 6001/6001 | 6/8 | 1070.05 | 是 |
| single | 2 | 60000 | 7.68/34.22/50.15 | 0.000% | 0 | 6001/6001 | 4/5 | 1062.05 | 是 |
| single | 3 | 60000 | 7.77/34.35/50.95 | 0.000% | 0 | 6001/6001 | 6/6 | 1065.00 | 是 |
| double | 1 | 60001 | 7.95/38.25/57.65 | 0.000% | 0 | 6001/6001 | 4/4 | 1076.00 | 是 |
| double | 2 | 60000 | 7.83/36.41/53.03 | 0.000% | 0 | 6001/6001 | 4/6 | 1068.00 | 是 |
| double | 3 | 60001 | 7.81/36.40/53.27 | 0.000% | 0 | 6001/6001 | 4/4 | 1070.00 | 是 |
| search | 1 | 601 | 163.02/201.77/230.09 | 0.000% | 0 | 1/1 | 0/0 | — | 是 |
| search | 2 | 601 | 162.43/185.73/232.64 | 0.000% | 0 | 1/1 | 0/0 | — | 是 |
| search | 3 | 601 | 163.62/209.84/251.29 | 0.000% | 0 | 1/1 | 0/0 | — | 是 |
| attachment | 1 | 12001 | 4.11/5.35/7.28 | 0.000% | 0 | 1/1 | 0/0 | — | 是 |
| attachment | 2 | 12001 | 3.98/5.19/7.18 | 0.000% | 0 | 1/1 | 0/0 | — | 是 |
| attachment | 3 | 12001 | 4.12/6.57/13.61 | 0.000% | 0 | 1/1 | 0/0 | — | 是 |

搜索和附件组的受理任务为初始化验证邮件。混合负载的投递 P95 含客户端轮询间隔。原始分接口指标、时间连续性、资源配额和受理对账保存在 evidence/load/ 各轮目录。

| 组 | 通过/计划 | HTTP P95 三轮中位数 (ms) |
|---|---:|---:|
| single | 3/3 | 34.35 |
| double | 3/3 | 36.41 |
| search | 3/3 | 201.77 |
| attachment | 3/3 | 5.35 |

正式性能矩阵通过。机器可读汇总见 evidence/verified-final-matrix-summary.json。

RabbitMQ 停机时受理 50 封，服务恢复后全部送达，客户端观测从启动到全量完成耗时 63.157 秒。两实例均恢复每分钟 10 个令牌，故障注入关闭。最终隔离库中 139,743 个任务均已送达，未发布事件和重复收件记录均为零。见 evidence/backlog.json 和 evidence/verified-final-runtime.json。

双实例预热轮出现丢弃，原始记录单独保留。正式结果适用于本次预热后的本地环境、固定负载和数据，不代表冷启动或超过已测速率时的容量。混合负载在 100 迭代/秒时，单、双实例均达标，数据没有证明双实例会降低延迟。搜索结果对应固定关键词和热账号，罕见或无匹配关键词仍可能扫描更多记录。
