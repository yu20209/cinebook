# CineBook 并发与消息可靠性实验报告

> 全部数据均在本机实测得到，非估算。测试日期：2026-11-21。

## 0 测试环境

| 项 | 值 |
| --- | --- |
| 操作系统 | Windows（LAPTOP-QRO7VS5P），16 GB 内存 |
| JDK | Corretto 17.0.19 |
| 应用 | Spring Boot 3.3.13，端口 9090，`ORDER_EVENT_PUBLISHER=rabbit` |
| 数据库 | MySQL 8.0.34，库 `xm-film` |
| 缓存 | Redis（E:\Redis，6379） |
| 消息队列 | RabbitMQ 4.4.0 + Erlang OTP 27.3.4.18（E:\cinebook-middleware，5672 / 15672） |
| 连接池 | Hikari `maximum-pool-size=100`（环境变量 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=100`） |
| 测试数据 | 场次 `record_id=145`（2026-11-22 10:00，10 排 × 12 座，单价 38 元）；用户 `u1,u2,s01…s10` 共 12 个 |

连接池说明见 §4.1：应用默认 Hikari 池大小是 10，压 200 并发时会被连接池而不是业务逻辑挡住，因此测试前按其负载调大，这是压测前的必要环境调优，不是代码改动。

---

## 1 实验一：高并发抢座

### 1.1 目标

同一座位被多个用户同时抢购时，只允许一个有效订单锁定该座位，验证零超卖。

### 1.2 操作

1. 用 12 个用户分别登录取 JWT，并发请求按用户轮转携带 token。
2. 每一档并发使用**不同座位**，避免上一轮残留影响。
3. 所有请求**同一时刻放行**：每个请求一个独立线程，先阻塞在 `ManualResetEventSlim` 上，主线程 `Set()` 后同时发出（不是线程池排队，是真实同时发起）。
4. 每个请求带**唯一 requestId**（`exp1-{并发数}-{时间戳}-{序号}`），确保本实验只测座位争抢，不测幂等。
5. 每轮开始前清空 Redis 限流键（`rl:order:*`），避免单用户 20 次/分钟的限制干扰。
6. 每轮结束后立刻查库并清理该轮订单、把座位恢复为可用。

请求体：

```json
{"recordId":145,"seat":"10排N座","requestId":"exp1-<并发数>-<ts>-<i>"}
```

### 1.3 结果

| 并发数 | 成功 | 冲突失败(409) | 限流 | 其他失败 | 耗时 | 座位最终状态 | 有效订单数 | 不同锁单号 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 | 1 | 9 | 0 | 0 | 182 ms | status=1 已锁定 | 1 | 1 |
| 50 | 1 | 49 | 0 | 0 | 351 ms | status=1 已锁定 | 1 | 1 |
| 100 | 1 | 99 | 0 | 0 | 534 ms | status=1 已锁定 | 1 | 1 |
| 200 | 1 | 199 | 0 | 0 | 983 ms | status=1 已锁定 | 1 | 1 |

每轮查库语句：

```sql
SELECT record_id, seat_code, status, lock_order_no
FROM screening_seat WHERE record_id=145 AND seat_code='10排N座';

SELECT COUNT(*) FROM ordered
WHERE record_id=145 AND FIND_IN_SET('10排N座', REPLACE(seat,'，',','))
  AND status NOT IN ('已取消','已退票');
```

### 1.4 结论

- 200 并发下仍然**只有 1 个请求成功锁座**，其余 199 个全部返回 409「座位已被占用」，DB 中该座位只有 1 条有效订单、1 个 lock_order_no，**零超卖、零重复锁定**。
- 座位状态在并发结束后为 `status=1`（锁定），没有被并发写坏成 0 或 2。
- 耗时随并发线性增长（182 → 983 ms），说明请求在座位行上排队而不是互相踩踏；这是「同一座位」这个极端场景的必然结果，不代表系统吞吐。

---

## 2 实验二：订单幂等

### 2.1 目标

同一个 `requestId` 重复提交（含客户端超时重试）不产生重复订单。

### 2.2 操作与结果

**用例 A：顺序重复 10 次**

同一用户、同一 requestId、同一座位，串行提交 10 次。

```
返回码:      200,200,200,200,200,200,200,200,200,200
返回订单号:  2026112143B0D1A1（10 次完全相同）
DB 订单数:   1
总耗时:      101 ms
```

**用例 B：并发 20 次（同一 requestId）**

12 个用户轮转、同一 requestId、同一座位，同时发出 20 个请求。

```
返回码分布:  200  → 2 个
             409  → 18 个（"所选座位中部分或全部已被占用，请刷新选座图重试"）
DB 订单数:   1
座位状态:    status=1，lock_order_no 唯一
```

**用例 C：模拟客户端超时后重试**

第一个请求发出 5 ms 后主动 `Abort()`（模拟客户端超时断开），600 ms 后用**同一个 requestId** 重试。

```
第一次:     连接被客户端中止（服务端可能已处理，也可能未处理）
重试返回:   200
DB 订单数:  1
```

**用例 D：同一 requestId 换座位**

先以 requestId=X + 座位 A 下单，再以 requestId=X + 座位 B 提交。

```
首次:  200
第二次: 409（内容冲突，拒绝把上一单当成本次结果）
```

### 2.3 结论

- 顺序重试、超时重试都能**稳定返回同一笔订单，DB 只有 1 条**。
- 并发同 requestId 场景：DB 订单数恒为 1，幂等目标达成；但 20 个请求中只有 2 个拿到 200，其余 18 个在「锁座」阶段就被判为座位占用而返回 409——因为它们的幂等预查早于胜者提交，还没看到订单行。**这是可优化点**：锁座失败时应再查一次 requestId 是否已有订单，命中则回读原订单而不是报座位冲突。
- 同 requestId 换内容被拒绝，避免请求标识复用导致串单。

---

## 3 实验三：RabbitMQ 可靠性

### 3.1 场景一：消息发送失败（Broker 宕机）

**操作**

1. `Stop-Process` 杀掉 Erlang 进程，确认 5672 端口不可用。
2. 正常下单并支付（座位 10排12座，38 元）。
3. 查订单、Outbox、通知。
4. 启动 Broker，等待 Outbox 自动补偿。

**结果**

| 检查点 | 实测 |
| --- | --- |
| 支付响应 | code=200，耗时 129 ms（Broker 不可用不影响支付事务） |
| 订单状态 | 待取票（已支付成功） |
| Outbox | status=PENDING，retry_count=1，error_msg=`java.net.ConnectException: Connection refused` |
| 通知表 | 0 条（事件尚未投递） |
| 支付时间 | 19:55:57 |
| Broker 就绪 | 19:56:34（启动耗时 5.3 s） |
| Outbox 转 SENT | 19:56:40（Broker 就绪后 **6.1 s**，即下一次 10 s 退避重试） |
| 端到端恢复 | **43 s**（含两次失败退避等待） |
| 通知表最终 | 1 条：「订单 2026112155615310 支付成功，座位 10排12座，金额 38.0 元」 |

**结论**：Broker 完全不可用时，支付事务照常提交，事件落在 Outbox 中不会丢；Broker 恢复后由定时任务自动补发，无需人工干预，恢复后通知恰好 1 条。

### 3.2 场景二：消费者异常退出

**操作**

1. 先建单（待支付）。
2. 通过管理 API 批量 `DELETE /api/connections`，踢掉所有 AMQP 连接（模拟消费者进程异常退出）。
3. 立即支付，并在随后 8 s 内持续踢连接，阻止消费者立即重连。
4. 停止踢连接，等待消费者自动恢复并消费。

**结果**

```
支付时刻:            20:00:46.805
Outbox 投递成功:     20:00:47（SENT）
消费完成（通知落库）: 20:00:55.462
消费延迟:            8.7 s
最终通知条数:        1
队列积压（恢复后）:  0
```

**结论**：消费者不可用期间消息留在 Broker 队列中（持久化队列），**没有丢失也没有重复**；消费者恢复后自动重新消费，最终只落 1 条通知。管理 API 的队列深度统计有约 5 s 刷新延迟，因此采样到的瞬时深度为 0，实际以「投递时间 → 消费时间」的 8.7 s 间隔为准。

### 3.3 场景三：重复消费

**操作**

把已投递成功的事件（eventId=`6f7e6414…`）状态重置为 `PENDING` 并清空退避时间，让 Outbox 用**同一个 eventId** 再发一次，模拟消息重复投递。

```sql
UPDATE outbox_event
SET status='PENDING', retry_count=0, next_retry_at=NOW(), error_msg=NULL,
    claimed_by=NULL, claimed_until=NULL
WHERE aggregate_id='2026112155615310';
```

**结果**

| 检查点 | 实测 |
| --- | --- |
| Outbox 二次投递 | status=SENT |
| `mq_consume_log` 中该 eventId | 1 条 |
| `order_notification` 中该 eventId | 1 条 |
| 该订单通知总数 | 1 条 |
| 消费端日志 | `重复事件已忽略: eventId=6f7e6414b4c94b2dbd6bbff54b7069b9` |

**结论**：重复投递被 `mq_consume_log UNIQUE(consumer_name,event_id)` 拦截，业务侧没有产生第二条通知，幂等生效。

### 3.4 场景四：死信队列

**操作**

通过管理 API 向业务交换机投递一条**缺少 messageId** 的非法消息：

```json
{"properties":{},"routing_key":"cinebook.order.created",
 "payload":"{\"orders\":\"dlq-test\"}","payload_encoding":"string"}
```

**结果**

| 检查点 | 实测 |
| --- | --- |
| 投递结果 | `routed=true` |
| 业务队列 | messages=0，consumers=1 |
| 死信队列 `cinebook.order.dlq` | **messages=1** |
| 消费端日志 | `订单事件消息解析失败，转入死信队列: 消息缺少 messageId（事件编号）` |

验证后已清空死信队列，两队列均回到 0。

**结论**：不可解析的消息不会无限重回队列，而是 `basicNack(requeue=false)` 后按队列的 DLX/路由键进入死信队列，保留现场供人工排查；正常业务消息不受影响。

### 3.5 实验三汇总

| 指标 | 结果 |
| --- | --- |
| 有效业务事件数 | 2（场景一、场景二各 1 笔支付） |
| 最终处理成功数 | 2 |
| 消息最终处理成功率 | **100%** |
| 消息丢失 | 0 |
| 重复业务生效 | 0（重复投递被幂等跳过） |
| Broker 故障恢复耗时 | 43 s（Broker 就绪后 6.1 s） |
| 消费者故障恢复耗时 | 8.7 s |
| 死信隔离 | 1 条非法消息正确进入 DLQ，业务队列未积压 |

---

## 4 实验中发现的问题

### 4.1 连接池是并发上限（重要）

第一轮实验（未调大连接池，Hikari 默认 10）出现：

```
10 并发：成功 0，全部 500「系统错误」，耗时 31 s
后台异常：CannotCreateTransactionException: Could not open JDBC Connection for transaction
```

原因：Hikari 默认 `maximum-pool-size=10`、`connection-timeout=30s`。并发数超过池大小时，多出来的请求在获取连接处等待 30 s 后抛异常，被兜底处理器转成 500。**这不是座位逻辑的问题，而是资源池配置问题**。把池调到 100 后重测，四档并发全部得到「1 成功 + N-1 个 409」，无 500。

生产建议：按「最大并发事务数 ≈ Tomcat 线程数」估算连接池，并配合数据库 `max_connections`，而不是沿用默认 10。

### 4.2 并发同 requestId 的 409 可优化

如 §2.3 所述：并发重复请求虽然不会多建单，但大多数返回 409 座位占用提示，体验上像失败。可在「锁座失败」分支补一次按 requestId 的回查，命中则返回原订单。

---

## 5 复现方式

```powershell
# 1) 启动 RabbitMQ（E 盘便携版）
$env:ERLANG_HOME='E:\cinebook-middleware\erl'
$env:RABBITMQ_BASE='E:\cinebook-middleware\rabbitmq-data'
& 'E:\cinebook-middleware\rabbitmq\rabbitmq_server-4.4.0\sbin\rabbitmq-server.bat'

# 2) 启动后端（压测前调大连接池）
$env:JAVA_HOME='C:\Users\yu''xuan\.jdks\corretto-17.0.19'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='100'
mvn -o spring-boot:run

# 3) 造可用场次（演示数据过期时平移 7 天）
#    UPDATE record  SET start = DATE_ADD(start, INTERVAL 7 DAY);
#    UPDATE ordered SET start = DATE_ADD(start, INTERVAL 7 DAY) WHERE start IS NOT NULL;

# 4) 跑实验：每个实验脚本用 12 个用户 token，并发用独立线程 + 统一放行
```

实验脚本位于本机临时目录（`%TEMP%\exp1b.ps1`、`exp2.ps1`、`exp3a.ps1`、`exp3b2.ps1`），核心逻辑：多线程同时发请求、统一放行；用 `curl.exe`/管理 API 查询队列深度；用 MySQL 查询座位与订单终态。
