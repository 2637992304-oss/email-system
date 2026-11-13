# 前端应用

本目录存放智能邮件管理系统的 Vue 前端应用。前端提供登录、注册、邮箱主界面、邮件收发、搜索筛选、附件上传下载、星标联系人、智能排序和未读通知等交互能力。

## 技术栈

- Vue 3
- Vue Router
- Vite 6
- Axios
- 原生 CSS

## 目录结构

```text
frontend/
├─ src/
│  ├─ api/                         # 后端接口封装
│  │  ├─ auth.js                   # 登录、注册、退出、当前用户接口
│  │  ├─ http.js                   # Axios 实例、JWT 注入、响应格式校验、401 处理
│  │  └─ mail.js                   # 邮件、附件、智能、星标、联系人接口
│  ├─ components/                  # 扁平复用组件
│  │  ├─ FolderSidebar.vue         # 邮箱侧边栏和文件夹导航
│  │  └─ ToastStack.vue            # 页面 toast 提示栈
│  ├─ helpers/
│  │  └─ mail.js                   # 邮件常量、工具函数、toast、SSE
│  ├─ router/
│  │  └─ index.js                  # 路由表和登录守卫
│  ├─ stores/
│  │  └─ auth.js                   # token、当前用户、登录/退出状态管理
│  ├─ styles/
│  │  ├─ main.css                  # CSS 入口，统一 import 其他样式
│  │  ├─ base.css                  # 全局变量、reset、基础元素样式
│  │  ├─ auth.css                  # 登录和注册页样式
│  │  ├─ dashboard.css             # 邮箱主布局、侧边栏、工具区
│  │  ├─ mail-list.css             # 邮件列表和邮件行
│  │  ├─ mail-detail.css           # 邮件详情面板
│  │  ├─ components.css            # 弹窗、按钮、toast 等复用组件
│  │  └─ responsive.css            # 窄屏适配
│  ├─ views/
│  │  ├─ LoginView.vue             # 登录页
│  │  ├─ RegisterView.vue          # 注册页
│  │  └─ DashboardView.vue         # 邮箱主界面
│  ├─ App.vue                      # 应用根组件
│  └─ main.js                      # Vue 应用入口
├─ index.html                      # Vite HTML 入口
├─ package.json                    # npm 脚本和依赖
├─ package-lock.json               # 锁定依赖版本
├─ vite.config.js                  # Vite 配置和 /api 代理
└─ README.md
```

## 页面与路由

| 路由 | 页面 | 权限 | 说明 |
|---|---|---|---|
| `/` | 重定向 | - | 默认跳转到 `/dashboard` |
| `/login` | `LoginView.vue` | 游客 | 登录表单 |
| `/register` | `RegisterView.vue` | 游客 | 注册表单 |
| `/dashboard` | `DashboardView.vue` | 登录用户 | 邮箱主界面 |

路由守卫位于 `src/router/index.js`。访问需要登录的页面时，如果没有 token，会跳转到登录页；已登录用户访问登录或注册页时，会回到邮箱主界面。

## 前端分层

| 层级 | 目录 | 作用 |
|---|---|---|
| 页面层 | `src/views` | 负责页面编排和跨模块状态组织 |
| 组件层 | `src/components` | 承接可复用 UI，例如侧边栏、toast 栈 |
| 接口层 | `src/api` | 登录接口、邮件相关接口和统一 HTTP 客户端 |
| 公共逻辑 | `src/helpers` | 邮件常量、纯函数、toast 自动清理和 SSE 生命周期 |
| 状态层 | `src/stores` | 保存登录态、token、当前用户等轻量全局状态 |

## API 封装

| 文件 | 作用 |
|---|---|
| `src/api/http.js` | 创建 Axios 实例，请求时注入 JWT，响应时校验 `ApiResponse`，遇到 401 清理登录态并跳转登录页 |
| `src/api/auth.js` | 封装 `/api/auth` 和 `/api/user/me` |
| `src/api/mail.js` | 封装邮件、附件、优先级、通知、垃圾识别、星标和联系人名单 |

后端响应格式应为：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

如果接口返回 blob，例如附件下载，`http.js` 会直接返回二进制数据。`VITE_API_BASE_URL` 会同时用于普通 Axios 请求和 SSE 连接；开发环境为空时默认走 Vite `/api` 代理。

## 状态管理

`src/stores/auth.js` 使用 Vue `reactive` 保存登录态：

- `token`：从 localStorage 的 `email-system-token` 读取。
- `user`：当前登录用户信息。
- `loadingUser`：路由守卫加载用户时的状态标记。

登录成功后会保存 token；退出登录或收到 401 后会清理 token 和用户信息。

`DashboardView.vue` 保留页面级编排和必要的业务协调。反馈提示、SSE 连接和重连、邮件列表归一化等公共逻辑集中在 `src/helpers/mail.js`，避免在页面里重复写，又不会把项目拆得太散。

## 开发启动

安装依赖：

```bash
cd frontend
npm install
```

启动开发服务：

```bash
npm run dev
```

默认访问地址：

```text
http://localhost:5173
```

`vite.config.js` 中配置了开发代理：

```js
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true
    }
  }
}
```

因此前端代码中可以直接请求 `/api/...`，无需写完整后端地址。

## 主要功能入口

| 功能 | 主要位置 |
|---|---|
| 登录注册 | `LoginView.vue`、`RegisterView.vue`、`src/api/auth.js` |
| 邮件列表和详情 | `DashboardView.vue`、`FolderSidebar.vue`、`src/api/mail.js` |
| 写邮件与附件 | `DashboardView.vue`、`src/api/mail.js` |
| 搜索与筛选 | `searchMails`、`filterMails` |
| 已读/未读 | `markMailRead`、`markMailUnread` |
| 垃圾箱 | `getTrashMails`、`restoreMail`、`permanentlyDeleteMail` |
| 智能整理 | `src/api/mail.js`、`getPriorityList`、`organizeMails` |
| 未读通知 | `src/api/mail.js`、`src/helpers/mail.js` |
| 星标和联系人 | `src/api/mail.js`、`addStar`、`removeStar`、`getContactList` |

## 常见问题

### 页面打开后接口全部失败

先确认后端是否启动在 `http://localhost:8080`。前端开发服务只负责页面和代理，不会替代后端服务。

### 浏览器控制台显示 401

登录态失效或 token 不存在。清理浏览器 localStorage 中的 `email-system-token` 后重新登录。

### 直接打开 `index.html` 后接口不通

不要直接用浏览器打开 HTML 文件。必须通过 `npm run dev` 启动 Vite，`/api` 代理才会生效。

### 配置了独立后端地址但 SSE 不连接

检查 `.env` 中的 `VITE_API_BASE_URL`。该值现在会同时影响 Axios 请求和 `EventSource` SSE 地址；如果后端单独部署，需要填写后端完整 origin，例如 `http://localhost:8080`。

### 端口 5173 被占用

Vite 可能会自动尝试其他端口，也可以在 `vite.config.js` 中修改 `server.port`。如果修改端口，访问地址也要同步变化。

### 修改样式后没有变化

确认样式文件已被 `src/styles/main.css` 引入，或组件中实际使用了对应 class。浏览器缓存异常时可以强制刷新页面。

### 附件下载没有反应

检查 Network 中 `/api/attachment/download/{id}` 是否返回成功；如果返回 401，先重新登录；如果后端返回文件不存在，需要检查后端附件目录和数据库记录。

## 与后端协作约定

- 所有业务接口走 `/api` 前缀。
- 普通接口返回 `ApiResponse<T>`。
- 登录成功返回的数据需要包含 token 和用户基本信息。
- 需要登录的接口由前端自动携带 `Authorization: Bearer <token>`。
- 附件下载接口使用 blob 响应，不走普通 JSON 数据解析。
