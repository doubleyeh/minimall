# 前端开发规范:Vue3 + TypeScript + Vite(RBAC + 多租户管理端)

配套后端 `architecture.md`。接口路径、字段名、状态码语义以后端文档为准,前端不得自行变更。

---

## 0. 范围

**做**:登录/令牌生命周期、动态路由、按钮级权限、租户上下文、系统管理页面(用户/角色/部门/菜单/字典/套餐/租户/操作日志)。

**不做**:SSR、微前端、i18n、移动端适配、暗色模式、前端埋点。管理端按 ≥1280px 桌面设计,文案硬编码中文。

---

## 1. 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| 框架 | Vue | **3.5.x,禁止使用 3.6 及 Vapor Mode** |
| 语言 | TypeScript | `strict: true` |
| 构建 | Vite | 最新稳定版 |
| UI | **Naive UI** | 2.x |
| 路由 | Vue Router | 4.x |
| 状态 | Pinia | 3.x |
| HTTP | axios | 1.x |
| 图标 | `@vicons/*` | 只选一套,禁止混用多套 |
| 按需引入 | `unplugin-auto-import` + `unplugin-vue-components` | 配 `NaiveUiResolver()` |
| 规范 | ESLint + Prettier | flat config |

`package.json` 锁 `vue: ^3.5.x`,禁止写 `^3.6` 或 `latest`。

### 1.1 按需引入配置

- `unplugin-vue-components` 配 `NaiveUiResolver()` 引入组件。
- `unplugin-auto-import` 的 `imports` 中显式声明从 `naive-ui` 导入:`useMessage`、`useDialog`、`useNotification`、`useLoadingBar`,以及类型 `MenuOption`、`DataTableColumns`、`FormInst`、`TreeOption`、`GlobalThemeOverrides`。resolver 不处理这些。
- 用 `n-global-style` 统一全局字体与背景重置。

### 1.2 Provider 嵌套(强制)

根组件按此顺序嵌套,缺层会导致对应 `useXxx()` 抛错、页面白屏:

```
n-config-provider          ← 必须传 :locale="zhCN" :date-locale="dateZhCN"
  └─ n-loading-bar-provider
      └─ n-dialog-provider
          └─ n-message-provider
              └─ n-notification-provider
                  └─ 应用内容
```

不传 `locale` / `dateLocale` 时日期选择器、分页器、表格空状态显示英文。

---

## 2. 工程结构

```
src/
  api/          接口封装,按后端业务域分文件。只发请求 + 声明类型,不写业务逻辑、不弹提示
  assets/
  components/   与业务无关的通用组件
  composables/  组合式函数
  directives/   v-perm(见 5.3)
  layout/       侧边栏 + 顶栏 + 标签页 + 内容区
  router/       路由定义、守卫、动态路由生成
  stores/       user / permission / app
  types/        与后端 DTO 一一对应
  utils/        request(axios 封装)、auth(令牌存取)、discrete(见 3.2)
  views/        按业务域分目录
```

- `views` 目录结构必须与后端 `sys_menu.route_path` 可映射(见 5.2)。
- `api/` 一个函数对应一个后端接口,函数名与后端路径保持可追溯。
- 超过一屏的业务逻辑抽到 `composables/`,不留在组件内。

---

## 3. 请求层

### 3.1 响应拆包

后端统一返回 `{ code, message, data }`。响应拦截器中:

- `code === 0`:向业务代码返回 `data` 本身,业务层不再写 `.data.data`。
- `code !== 0`:弹错误提示并 reject。
- 需自行处理错误的接口(如表单字段级报错),通过请求配置声明跳过全局提示。

### 3.2 拦截器弹提示必须用 `createDiscreteApi`(强制)

**禁止**在 `request.ts` 等模块中调用 `useMessage()`。该 API 依赖组件上下文,模块顶层调用不会在编写期报错,首次触发时抛 "must be called inside setup" 或取到 undefined。

做法:在 `utils/discrete.ts` 中用 `createDiscreteApi` 创建 `message` / `dialog` / `notification` 实例并导出,供拦截器及其他非组件代码使用。创建时传入 `configProviderProps`,与 `n-config-provider` 使用同一份主题配置。

### 3.3 请求头

| 头 | 何时带 | 取值 |
|---|---|---|
| `Authorization` | 所有已登录请求 | 登录响应的 `token` |
| `X-Tenant-Code` | 仅白名单外的公开接口 | `.env` 的 `VITE_TENANT_CODE`,禁止全局默认带上 |
| `X-Trace-Id` | 不发送 | 从响应头读取,报错时展示/上报 |

### 3.4 状态码处理

| 码 | 动作 |
|---|---|
| 401 | 走 4.2 刷新流程;刷新失败才跳登录页 |
| 403 | 走 5.4;不清令牌、不跳登录页 |
| 429 | 提示"操作过于频繁,请稍后再试";**禁止自动重试** |

---

## 4. 认证与令牌

### 4.1 令牌存储

`token` 与 `refreshToken` 均存 `localStorage`。刷新成功后必须在同一时刻覆盖写入新的一对,禁止出现"已用新 token、仍存旧 refreshToken"的状态。

请求发出前**始终从 `localStorage` 现读**令牌,禁止缓存在模块变量中。

### 4.2 并发刷新单飞(强制)

后端刷新令牌为一次性轮换并带重放检测:同一 refreshToken 被使用第二次,该用户全部会话被撤销。页面并发请求同时 401 时若各自发起刷新,用户被强制登出。

实现:

```
模块级 refreshPromise: Promise<TokenPair> | null

响应拦截器收到 401,且该请求不是刷新请求:
  1. refreshPromise 为 null → 本请求发起刷新,Promise 存入 refreshPromise
     否则 → 不发起刷新,await 已有的 refreshPromise
  2. await 结果:
     成功 → 用新 token 重写本请求 Authorization,重放原业务请求
     失败 → 清空本地令牌,跳登录页
  3. finally 中将 refreshPromise 置回 null
```

配套强制规则:

1. 每个业务请求因 401 最多重试一次。请求配置打标记(如 `_retried`),重试后再 401 直接失败跳登录。
2. 拦截器需先判断失败请求是否为 `/auth/refresh`,是则直接走登出流程,禁止触发刷新。
3. 禁止定时轮询式主动刷新,仅在收到 401 时刷新。

### 4.3 刷新失败

清空两个令牌与 user / permission store → 跳登录页 → 提示"登录状态已失效,请重新登录"。

- **禁止**自动重试刷新。
- 后端刷新失败文案统一,前端原样展示,不解读原因、不在 UI 上区分。

### 4.4 登录

`POST /auth/login`,请求体 `{ tenantCode, username, password }`。

- `tenantCode` 为必填输入项;可用 `localStorage` 记忆上次输入的租户编码,禁止记忆用户名密码。
- 密码明文提交,**禁止**前端加 md5 或任何加密。
- 登录失败文案由后端统一返回,**禁止**前端区分"租户不存在/用户不存在/密码错误"。

成功后按序执行:

1. 存两个令牌
2. `userId / tenantId / isSuperUser / nickname` 写入 user store
3. `menus / permCodes` 写入 permission store,生成动态路由(5.2)
4. 判断 `mustChangePassword`(4.5),决定跳改密页或首页

### 4.5 强制改密

`mustChangePassword: true` 时,除 `/auth/password`、`/auth/logout`、`/auth/permissions` 外所有请求返回 403。

- 路由守卫强制跳改密页,拦截手动改地址栏绕过。
- 改密成功后后端使该用户全部会话失效,前端必须清空令牌并跳登录页重新登录,禁止继续使用旧令牌。

### 4.6 登出

`POST /auth/logout`,**请求体必须带 `refreshToken`**(不带则撤销该用户全部设备令牌)。

随后清空令牌、user store、permission store,并重置动态路由(见 5.2)。

### 4.7 多标签页

按 4.1 要求每次现读 `localStorage` 即可。不实现跨标签页令牌同步锁。

---

## 5. 权限

### 5.1 权限数据

`menus` / `permCodes` 来自登录响应,为当时的快照。需要更新时调 `GET /auth/permissions` 重新拉取。不做轮询、不做推送。

### 5.2 动态路由

`sys_menu` 映射规则:

| menu_type | 处理 |
|---|---|
| 1 目录 | 生成含 children 的父级路由,组件为 Layout 或空壳 |
| 2 页面 | 生成真实路由,`route_path` 为路径,按约定映射组件 |
| 3 按钮 | **不生成路由**,`perm_code` 收入权限集合供 `v-perm` 使用 |

**组件映射约定**:用 `import.meta.glob('/src/views/**/*.vue')` 建立映射,`route_path = /system/user` 对应 `views/system/user/index.vue`。后端新增菜单须按此约定填写 `route_path`。

强制规则:

1. 登录页、404、403、改密页为静态路由;业务页面全部动态添加。
2. **404 通配路由必须在动态路由添加完成之后再添加**,否则刷新页面时业务路径被通配拦截。
3. 登出时必须移除动态路由。记录 `router.addRoute` 返回的移除函数并逐个调用,或重建 router 实例。未重置会导致换账号后残留越权路由。
4. 路由守卫中判断"有令牌但 permission store 为空"(页面刷新场景)→ 调 `GET /auth/permissions` 重建路由 → `next({ ...to, replace: true })`。

### 5.3 `v-perm` 指令

- 接收一个或多个 `perm_code`,在 permission store 集合中查不到则移除元素。
- `perm_code` 格式 `模块:资源:操作`,**原样使用**,禁止前端拼接或改造。
- `isSuperUser = true` 时后端返回全部 `perm_code`,前端不写超管分支。
- 指令仅控制 UI 显隐,不作为安全边界。

### 5.4 403 处理

1. 调用一次 `GET /auth/permissions` 刷新快照
2. 重建菜单与按钮状态
3. 仍失败 → 提示无权限,引导重新登录

同一会话内该刷新限频(如 10 秒一次)。

---

## 6. 页面实现要点

仅列与后端约束相关项,常规 CRUD 不展开。

### 6.1 角色授权(菜单树)

1. **候选菜单树从后端授权接口取**,禁止用登录响应的 `menus` 渲染(两者集合不同)。
2. **提交内容必须包含全部祖先节点**,后端校验不通过即失败,且不做静默过滤。`n-tree` 为受控组件,**无 `getCheckedKeys()` / `getHalfCheckedKeys()` 方法**,不得套用 Element Plus 写法:
   - 通过 `v-model:checked-keys` 绑定选中项,半选节点由 `v-model:indeterminate-keys` 取得,提交前合并两者;
   - 或设 `check-strategy="all"` 使选中项自带父节点。
   - 二选一并在代码注释中写明,禁止混用。
3. `is_default = 1` 的默认管理员角色禁止人工编辑菜单,前端禁用其授权入口并说明。
4. 禁止前端对越界提交做过滤兜底。

### 6.2 角色数据权限

`data_scope` 五档,仅 `4-自定义部门` 显示部门树多选,其余隐藏。切换档位时清空已选部门并提示。

### 6.3 用户管理

- 新建用户 / 重置密码返回的初始明文密码**仅返回一次**,须弹窗展示并提供复制,提示立即保存。
- 任何接口不返回 `password`,禁止做密码回显。

### 6.4 租户管理(超管可见)

- 禁用租户/改有效期后为惰性生效(最长 60 秒),提示文案写"该租户用户将在下一次请求时退出",禁止写"已立即踢出"。
- 平台租户禁用/删除按钮置灰。

### 6.5 操作日志

`tenant_id` / `user_id` 允许为空,表格须正确渲染空值,禁止显示 "undefined"。

---

## 7. 环境配置

| 项 | dev | prod |
|---|---|---|
| `VITE_API_BASE_URL` | 走 Vite proxy | 实际后端地址 |
| Vite proxy | 开 | — |
| sourcemap | 开 | 关 |
| `VITE_TENANT_CODE` | 测试租户编码 | 按站点定 |

dev 跨域由 Vite proxy 解决,不要求后端开 CORS。生产若确定跨域部署,需后端补 CORS 配置(见第 9 节)。

---

## 8. 必验项

以下手工验证必须全部通过:

1. **并发 401 只刷新一次**:令牌过期后打开并发多请求页面,网络面板中 `/auth/refresh` 只出现一次,所有业务请求成功重放。
2. **刷新页面后动态路由恢复**:业务页面 F5,不白屏、不跳 404、菜单完整。
3. **换账号无残留路由**:A 登录 → 登出 → B(权限更少)登录,A 的菜单与路由不存在,手输 A 的路由地址进不去。
4. **拦截器错误提示可弹出**:构造业务错误码,页面出现错误提示。
5. **中文本地化生效**:含日期选择器与分页器的页面不显示英文。
6. 改密成功后强制重登。
7. 登出请求体带 `refreshToken`。

---

## 9. 遗留

1. 多标签页同一瞬间并发刷新仍可能触发重放登出,未做跨页协调。
2. 生产跨域部署的 CORS 未确认,需与后端确认。
3. `route_path` 与组件目录的映射为隐式约定,无自动校验,填错表现为白屏。
4. i18n、暗色模式、移动端适配未实现。
5. `GET /auth/permissions` 限频由前端自律,后端未做专门限流。
