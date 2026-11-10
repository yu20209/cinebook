# 架构

## 1 技术栈

| 层 | 选型 | 版本 |
| --- | --- | --- |
| 后端 | Spring Boot | 3.3.13 |
| | Java | 17 |
| | MyBatis（XML mapper）+ spring-boot-starter | 3.0.4 |
| | PageHelper | 1.4.6 |
| | jjwt | 0.11.5 |
| | spring-security-crypto（仅 BCrypt） | BOM 托管 |
| | Lombok / DevTools | BOM 托管 |
| | spring-boot-starter-data-redis | BOM 托管 |
| | spring-boot-starter-amqp | BOM 托管 |
| 数据库 | MySQL | 8.0.37；库名 `xm-film` |
| 中间件 | Redis 7（缓存 / Lua 限流）· RabbitMQ 3（Outbox 事件） | 均可缺省：缓存与限流降级、事件走本地直派 |
| 前端 | Vue | 3.5 |
| | Vue Router | 4.5 |
| | Element Plus | 2.9 |
| | ECharts（按需引入） | 6.0 |
| | Vite | 6.4 |
| | Sass | 1.89 |

前端已接入 **ESLint**（`npm run lint`）与 **Vitest**（`npm test`），未用 Prettier；组件与端到端仍靠 `npm run build` + 无头 Chrome。后端无 H2 / Testcontainers，集成测试直连 `xm_film_test`。

## 2 进程与端口

| 进程 | 端口 | 备注 |
| --- | --- | --- |
| 后端 | 9090 | 只提供 `/api/v1/**` JSON + `/files/**` 静态图 |
| 前端 dev | 5173 | `vite.config.js` **只代理 `/files`**，`/api` 走 `.env.development` 的绝对地址 |
| 测试（ci profile） | 0 | 随机端口，库 `xm_film_test` |

`application.yml` 的关键配置项（密钥/口令/TMDB token/高德 Key 走 `.env`；代理、上传目录等本机参数仍直写）：

| 键 | 值 | 说明 |
| --- | --- | --- |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/xm-film?...` | 唯一数据源 |
| `jwt.signing-key` | `JWT_SIGNING_KEY`（`.env`） | 启动时校验长度 ≥ 32 字节；**刻意不叫 `secret`**：本机有用户级环境变量 `JWT_SECRET` 会被 Spring 宽松绑定覆盖 |
| `jwt.expire` | 86400000（24h） | |
| `file.upload-dir` | `D:/project/picture` | 上传落盘目录；`access-prefix` 指向 `http://localhost:9090/files/` |
| `tmdb.*` | token 从 `.env`，代理 `127.0.0.1:7897` | 本机直连 TMDB 恒超时 |
| `amap.key` / `request-interval-ms` | `AMAP_KEY`（`.env`）+ 300ms | 个人 Key QPS ≈5 |

DB 口令、TMDB token、高德 Key、JWT 密钥均从 `.env` 读取（SEC-02 修复），登录密码明文回退已删除（SEC-03 修复）；后端启动前必须提供 `JWT_SIGNING_KEY`，不再按“仓库自带密钥”运行。其余本机连接参数（代理、上传目录）仍直写，属既定演示政策。

## 3 后端分层

```
controller/  (23)  ── 只做 请求→实体→视图 映射，统一返回 Result<T>
   │
service/     (26)  ── 业务规则与事务边界，BaseService<T> 兜底 CRUD
   │
mapper/      (17)  ── BaseMapper<T> 六个语句；XML 在 resources/mapper/
   │
entity/      (17)  ── 与表一一对应（Account 例外：三账号的父类，无表）
   │
dto/request (32) dto/response (34) ── HTTP 边界只用 DTO，实体不出 controller
```

每层文件数（2026-10-27 实测）：controller 23 / 2,046 行，service 26 / 4,176 行，mapper 接口 17 / 467 行，mapper XML 17 / 1,885 行，entity 17 / 564 行，dto 66 / 1,471 行。

**核心基类**（`common/`）：

| 类 | 职责 | 关键约定 |
| --- | --- | --- |
| `BaseController<T,R,V>` | `GET /`、`GET /{id}`、`GET /page`、`POST`、`PUT`、`DELETE /{id}`、`DELETE /batch` | 子类只实现 `toEntity` / `toView`；`toPageView` 拷贝分页元数据 |
| `BaseService<T>` | CRUD + 事务注解 | `update` **先判存在**（NOT_FOUND 的唯一出处）；`deleteBatch` 空集合直接返回 |
| `BaseMapper<T>` | `selectAll / selectById / insert / updateById / deleteById / deleteBatch` | XML 的 `updateById` 结尾一律有 `id = #{id}` 兜底，防 `<set>` 退化为 1064 |
| `Identifiable` | `getId/setId` | 实体统一实现 |

有业务规则的子类**覆写** `add/update/delete` 或新增 `*Scoped` 方法（Record / Room / Ordered / User 均如此）。

## 4 统一响应与异常

`Result<T> { code: String, msg: String, data: T }`

- 成功 `code = "200"`；**业务失败也返回 HTTP 200**，业务码写在 body（`ErrorCode`）。
- 唯一的 HTTP 非 200 来自 `AuthInterceptor`：未登录 401、越权 403（body 同样是 `Result`）。
- `ErrorCode`：401 / 403 / 400 / 404 / 409 / 415 / 500。
- `CustomException` → `GlobalExceptionHandler`（`@ControllerAdvice` 限定 controller 包）。

**异常处理器分六档**（2026-10-18 补齐，此前只有"业务异常 + 兜底"两档）：

| 异常 | 返回 |
| --- | --- |
| `CustomException` | 自己的 code/msg |
| `MethodArgumentNotValidException` | 400 + 字段错误（`@Valid` 失败） |
| `MethodArgumentTypeMismatchException` | 400「参数「x」格式不正确」 |
| `MissingServletRequestParameterException` | 400「缺少必填参数「x」」 |
| `HttpMessageNotReadableException` | 400「请求体缺失或格式不正确」 |
| `HttpRequestMethodNotSupportedException` / `HttpMediaTypeNotSupportedException` | 400 / 415 |
| `DataIntegrityViolationException` | 409（外键/唯一键，不透露约束名） |
| `RestClientException` | 500 + TMDB 代理提示 |
| `Exception`（兜底） | 500「系统错误」——**只该接服务端自身的问题** |

**硬规则**：调用方引起的错误**不得**落到兜底。若某类请求返回「系统错误」，正确做法是加具名处理器，而不是放宽兜底。

> 由来：2026-10-18 实测发现 11 条路径（参数类型错、缺必填参数、空请求体、空对象请求体）全部返回 500。三次并发工单（BL-01/02/03）都撞在这同一个点上。

## 5 鉴权

三层：

1. **传输层**：`common/config/AuthInterceptor`（Spring `HandlerInterceptor`，非 Spring Security 过滤器链）。
   - `ADMIN_ONLY_PREFIXES`：admins / cinema-directory / tmdb —— 整条前缀按方法无关拦下。
   - `ADMIN_WRITE_PREFIXES`：films / actors / areas / types / notices / videos —— 只拦写方法。
   - `PUBLIC_READ_PREFIXES`：films / cinemas / types / areas / notices / actors / records / marks —— GET 匿名可读。
   - `ANONYMOUS_WRITE_EXACT`：`/api/v1/tickets/redeem` —— **精确路径 + 只 POST**。
   - 前缀匹配一律走 `matchesPrefix`（相等或 `前缀 + '/'`），禁止裸 `startsWith`。
2. **业务层**：`AuthContext.role()` / `userId()` 从拦截器写入的 request attribute 读；service 内做归属判定（`ensureOrderAccess`、`applyCinemaScope`、`ensureRecordAccess`）。
3. **前端层**：路由守卫（`router/index.js` 的 `beforeEach`）+ `useAuth` 单例。

**已知不对称**：`OrderedService.applyScope` 对未知角色不加过滤（列表放行），而 `ensureOrderAccess` 未知角色默认拒绝。token 里的角色受库级 `CHECK` 与 `RoleEnum` 约束，实际不可达；见 `audit/backend-backlog.md`。

## 6 数据边界

- 实体与表一一对应，**join 派生字段**（`Film.areaName/typeList`、`Ordered.filmName` 等）直接挂在实体上；这是既定取舍，不打算改。
- 金额：`unitPrice` / `ordered.total/payAmount/refundAmount` / `recharge_order.amount` / `fund_flow.*` 均为 `BigDecimal`；唯一例外是已废弃、恒为 0 的 `film.box_office`。
- 时间：绝大多数是 `String`（`yyyy-MM-dd HH:mm:ss`），唯一例外 `CinemaDirectory.createTime` 是 `LocalDateTime`（无消费者，不打算改）。
- 状态：中文枚举值（`common/enums/OrderStatus` 等），库端已加 `CHECK`（film/record/ordered/recharge_order/cinema 的 status 与三张账号表的 role），非法值由数据库拒绝（2026-10-27，`StatusConstraintLoadTest`）。

## 7 前端结构

```
src/
├── main.js            挂载 + 全局 errorHandler
├── App.vue            ElConfigProvider(zhCn) → ErrorBoundary → RouterView；按路由切 html.theme-front
├── router/index.js    路由表 + 唯一一个 beforeEach
├── views/             Front/Back/Manage 三个入口 + 共享 AdminShell + front/ back/ manage/ 41 页 + Login/Register/404
├── components/        6 个：FilmPosterCard / OrderPayDialog / PayPasswordInput / DetailSkeleton / ResetPasswordDialog / ErrorBoundary
├── composables/       6 个：useAuth / useCrud / useFormDialog / usePagedList / useLoginPrompt / useLogout
├── utils/             request / authStorage / format / upload / avatar / navigation
├── constants/         API 路径、状态映射、角色映射
└── assets/css/        tokens.scss（唯一数值权威）+ global.css + 4 个布局/页面 scss
```

详见 `frontend.md`。

## 8 高并发与事件一致性

### 8.1 座位库存

- 库存单位是"场次 + 座位"：`screening_seat(record_id, seat_code)` 唯一索引，状态 0 可用 / 1 锁定 / 2 售出，数据库 `CHECK` 约束兜底。
- 锁座是一条条件更新：`UPDATE ... WHERE record_id=? AND status=0 AND seat_code IN (...)`，受影响行数必须等于座位数，否则整单回滚（不会出现"部分座位锁上、部分没锁上"的超卖）。
- 建场次时在同一事务内按影厅行列生成座位；更换影厅时先校验 `countOccupied(record_id)=0`，无占用才清空并按新厅重建。
- `screening_seat.record_id` 外键 `ON DELETE CASCADE`：删除场次不会留下不可达的库存行。

### 8.2 下单幂等

- 请求必须带 `requestId`，`UNIQUE(user_id, request_id)` 是最终防线。
- 预查用即席查询快速返回原订单；并发落败方捕获唯一键冲突后，按 `record_id + 座位集合 + lock_order_no` 释放本次锁座（此时订单行不存在，不能依赖 `ordered` JOIN），再在新事务里读回对手提交的订单返回。
- 同一 `requestId` 换了场次或座位 → 判定为内容冲突，返回 409，避免把上一单当成本次结果。

### 8.3 缓存与限流

- 缓存键带版本号（`film:ver`），写操作让版本号 +1；写缓存与失效都注册在 `afterCommit`，事务回滚不会在缓存里留下未生效的新值。
- 下单限流用 Lua 脚本把 `INCR` 与 `PEXPIRE` 合成一次原子执行；Redis 异常时放行（数据一致性由数据库条件更新保证，不依赖限流）。

### 8.4 订单事件（Outbox → MQ）

- 支付/取消/退票在业务事务内写 `outbox_event`，与订单同生共死。
- 派发任务采用**认领 + 租约**：`UPDATE ... SET status='PROCESSING', claimed_by=?, claimed_until=? ... FOR UPDATE SKIP LOCKED`，多实例取到的事件互不重叠；实例崩溃后租约到期，事件被其它实例接手。只有 `claimed_by` 匹配的实例才能标记 SENT / 退回 PENDING / 转 FAILED。
- 失败按 `10 * 3^retry` 秒退避（上限 600s），重试 8 次后转 FAILED 保留现场。
- RabbitMQ 分支开启发布确认 + mandatory：NACK、确认超时、消息未路由都视为失败，事件保持未完成状态等待重试；消费端手动 ACK，业务异常重试 3 次后 `basicNack(requeue=false)` 进死信队列。
- 消费幂等：`mq_consume_log UNIQUE(consumer_name, event_id)` 跳过重复消费，`order_notification UNIQUE(event_id)` 兜住并发穿透；每消费一条事件落一条通知，而不是只打日志。
- 默认 `ORDER_EVENT_PUBLISHER=local`（进程内直派，不依赖 broker）；设为 `rabbit` 才连接真实 MQ。
