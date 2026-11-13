# 用户模块接口文档

## 统一返回格式

所有接口统一返回：

```json
{
  "code": 200,
  "message": "OK",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| code | int | 业务状态码，正常为 200 |
| message | string | 响应说明 |
| data | object | 响应数据 |

## 认证方式

登录成功后，后端返回 JWT。访问受保护接口时，请求头需要携带：

```http
Authorization: Bearer <token>
```

未登录、token 无效或 token 过期时返回：

```http
HTTP 401
```

```json
{
  "code": 401,
  "message": "Unauthorized",
  "data": null
}
```

## 1. 用户注册

```http
POST /api/auth/register
Content-Type: application/json
```

请求参数：

```json
{
  "email": "test@example.com",
  "username": "testuser",
  "password": "123456"
}
```

成功响应：

```json
{
  "code": 200,
  "message": "Registered",
  "data": null
}
```

常见失败：

| 场景 | HTTP 状态码 | code | message |
| --- | --- | --- | --- |
| 邮箱已注册 | 409 | 409 | Email already registered |
| 邮箱格式错误 | 400 | 400 | Invalid email format |
| 密码长度不合法 | 400 | 400 | Password length must be 6 to 32 |

## 2. 用户登录

```http
POST /api/auth/login
Content-Type: application/json
```

请求参数：

```json
{
  "email": "test@example.com",
  "password": "123456"
}
```

成功响应：

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "userId": 1,
    "username": "testuser",
    "email": "test@example.com",
    "token": "eyJhbGciOiJIUzI1NiIs..."
  }
}
```

常见失败：

| 场景 | HTTP 状态码 | code | message |
| --- | --- | --- | --- |
| 邮箱或密码错误 | 401 | 401 | Invalid email or password |
| 账号被禁用 | 403 | 403 | Account is disabled |

## 3. 获取当前用户信息

```http
GET /api/user/me
Authorization: Bearer <token>
```

成功响应：

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "userId": 1,
    "email": "test@example.com",
    "username": "testuser",
    "status": 1,
    "registerTime": "2026-05-28T16:18:37"
  }
}
```

说明：

- 该接口不返回密码。
- 该接口用于前端刷新页面后恢复当前登录用户信息。

## 4. 退出登录

```http
POST /api/auth/logout
Authorization: Bearer <token>
```

成功响应：

```json
{
  "code": 200,
  "message": "Logged out",
  "data": null
}
```

说明：

- 当前使用无状态 JWT，后端不保存 token。
- 前端调用该接口成功后，需要删除本地 token。
- 如果后续需要服务端强制 token 失效，可以增加 JWT 黑名单机制。

## 联调测试清单

| 编号 | 测试项 | 预期结果 |
| --- | --- | --- |
| 1 | 注册新用户 | 返回 200 |
| 2 | 重复邮箱注册 | 返回 409 |
| 3 | 正确邮箱密码登录 | 返回 200 和 token |
| 4 | 错误密码登录 | 返回 401 |
| 5 | 不带 token 访问 /api/user/me | 返回 401 |
| 6 | 带 token 访问 /api/user/me | 返回当前用户信息 |
| 7 | 带 token 调用 /api/auth/logout | 返回 200 |

