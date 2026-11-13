# 智能邮件管理系统

基于 Spring Boot、Vue 3 和 MySQL 8 的完整站内邮件系统，提供前后端源码、数据库结构和部署配置。

支持注册登录、收件箱/发件箱、抄送/密送、已读标记、星标、回收箱、附件上传下载、搜索筛选、联系人黑白名单/VIP、优先级整理、SSE 实时通知、规则与本地神经网络垃圾邮件识别、用户反馈学习，以及可选的大模型关键词提取、翻译和摘要。

邮件在本系统注册用户之间流转；本项目没有实现面向外部邮箱的 SMTP/IMAP 网关。

## 环境

- 推荐 JDK 21（源码目标 Java 17）、Node.js 22 LTS、MySQL 8.0。
- 后端提供 Maven Wrapper，无需额外安装 Maven；Python 3 用于可选的验收与扫描脚本。
- 或使用 Docker Engine / Docker Desktop 与 Compose v2。

## 配置

在项目根目录复制 `.env.example` 为 `.env`，填写以下配置。`.env` 不应提交到仓库。

| 变量 | 用途 |
| --- | --- |
| `DB_PASSWORD` | 自行设置的数据库应用用户密码，必填 |
| `JWT_SECRET` | 自行生成的随机签名密钥，至少 32 字节，必填 |
| `MYSQL_ROOT_PASSWORD` | Docker MySQL 管理员密码，使用 Compose 时必填，与应用密码不同 |
| `DB_USERNAME` | 数据库应用用户，默认 `mail_app`，不要设成 root |
| `DB_URL` | 本机 MySQL 连接地址，默认 localhost:3306/mail_system；Compose 内部自动覆盖 |
| `LLM_ENABLED` | 默认 false；配置 Key 后改为 true |
| `LLM_API_KEY` | 运行者自行申请的全新 Key，禁止复用旧仓库已公开的 Key |
| `LLM_BASE_URL` / `LLM_API_PATH` / `LLM_MODEL` | OpenAI 兼容供应商的地址、路径、模型 |

例如执行 `python -c "import secrets; print(secrets.token_hex(32))"` 生成一个随机值。为每个敏感配置分别生成，勿在日志或截图中公开。
配置文件使用不带引号的 `KEY=value`；建议密码/密钥使用随机十六进制字符串，避免 dotenv 与 Java properties 的转义差异。进程环境变量优先于 `.env`。

## Docker Compose 启动

在根目录填写 `.env` 后：

```sh
docker compose up -d --build
docker compose ps
```

访问 http://localhost:5173 ，注册两个账号即可互发邮件。数据库第一次启动会自动执行 `database/schema.sql`。
数据库和附件保存在独立的命名卷中；普通 `docker compose down` 保留数据。不要使用 `down -v`，除非明确要清空数据库和附件。
数据库与后端端口没有公开到主机，网页默认仅绑定本机。若部署到公网，请在入口配置 HTTPS、域名、访问策略及备份。

## 本机启动

1. 启动 MySQL 8，以管理员身份创建空库与应用用户。下面的密码是必须手动替换的占位符：

```sql
CREATE DATABASE mail_system CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'mail_app'@'localhost' IDENTIFIED BY '<自行设置的数据库密码>';
GRANT ALL PRIVILEGES ON mail_system.* TO 'mail_app'@'localhost';
```

2. 在项目根目录，用应用账号交互输入密码并初始化结构：

```sh
mysql --default-character-set=utf8mb4 -u mail_app -p mail_system
```

进入 MySQL 后执行 `SOURCE database/schema.sql;`。仅在空库执行该脚本；它不会删除既有表，也不预置任何账号或真实数据。

3. 填写根目录 `.env`，确保 `DB_PASSWORD` 与刚创建的账户一致。进入 `backend` 启动：

```powershell
# Windows PowerShell
.\mvnw.cmd spring-boot:run
```

```sh
# macOS / Linux
./mvnw spring-boot:run
```

后端默认 http://localhost:8080 ，健康检查为 `/api/health`。JWT 密钥缺失或少于 32 字节会阻止启动。

4. 另开终端，在 `frontend` 执行：

```sh
npm ci
npm run dev
```

访问 http://localhost:5173 。Vite 将 `/api` 代理到 8080；如果更换后端端口，请同步修改 `frontend/vite.config.js` 的代理目标。

## 构建与验证

```sh
# backend 目录，Windows 使用 .\mvnw.cmd
./mvnw test
./mvnw package
java -jar target/mail-system-0.0.1-SNAPSHOT.jar

# frontend 目录
npm ci
npm run build

# 根目录；后端运行中，且使用专用测试数据库
python scripts/smoke-test.py --base-url http://localhost:8080
python scripts/check-secrets.py
```

后端测试使用随机测试签名密钥；集成验收脚本会注册虚构账号并留下测试邮件，应对专用数据库运行。
生产前端构建位于 `frontend/dist`，需使用支持 Vue 路由回退、`/api` 转发和 SSE 的服务器，示例见 `frontend/nginx.conf`；直接打开 index.html 不会启动系统。

## 大模型功能

不配置 Key 可以完成注册、站内邮件、附件、联系人、规则/本地模型识别和反馈学习。翻译、摘要需要 `LLM_ENABLED=true` 和有效的 `LLM_API_KEY`，不可用时返回服务不可用提示。
默认配置指向智谱兼容接口；其他供应商请按其文档设置三个 LLM 参数。
启用后，相关邮件文本会发送给所选供应商，请先确认数据处理要求。

## 排障与安全

- 数据库连接失败：核对端口、数据库、应用用户授权和密码，先执行数据库初始化。
- 上传限制为单文件 10 MB；附件目录必须可写，数据库与附件目录需同时备份。
- Windows 某些隔离运行环境可能限制 Java 的 Unix socket；若报 `Unable to establish loopback connection`，优先使用标准 JDK 21 或 Docker 运行。本次验证使用 TCP 回环兼容方式，详情见验收记录。
- 不提交 `.env`、MySQL 数据目录、私钥、令牌、业务邮件、附件或运行日志。检查脚本只输出文件与行号，不能证明绝无泄露。
- 新仓库不继承旧仓库的提交历史。旧仓库公开过的凭据仍必须在供应商/数据库侧撤销或更换。

实际验收范围和限制见 [验收记录](docs/VERIFICATION.md)。源码来源见 [SOURCE.md](SOURCE.md)。
