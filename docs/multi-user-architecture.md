# 多用户并发访问改造设计

> 评估日期：2026-04-01
> 评估范围：`openclaw-java` 当前代码库

## 1. 背景

当前项目已经具备 `sessionKey` 维度的会话能力，也支持多个会话并行存在，但这并不等同于“多用户系统”。

如果目标是构建一个支持多个真实用户、多个业务会话、并发访问、权限隔离、资源隔离、审计追踪的系统，那么现在这套运行时更适合作为：

- 单实例 Agent 执行引擎
- 多会话运行时
- 被上层业务系统托管的内核

而不是直接作为完整的多租户多用户业务后端。

## 2. 现状判断

### 2.1 目前已经具备的能力

- 支持按 `sessionKey` 区分不同会话
- 支持会话 transcript 持久化
- 支持按会话进行模型调用和上下文恢复
- 支持多个会话对象同时存在于内存中
- Gateway / Channel / Agent 主链路基本都能传递 `sessionKey`

### 2.2 当前不应视为“多用户系统”的原因

#### 1. 缺少用户/租户一等公民模型

系统核心会话对象里只有 `sessionId`、`sessionKey`、`cwd`、模型信息等字段，没有 `tenantId`、`userId`、`principalId`、`orgId` 这类身份边界。

这意味着：

- 会话可以区分，但用户不能被系统强约束地区分
- 后续做权限、计费、审计、配额时没有稳定主键
- “谁拥有哪个会话”目前不是内核约束，而只是调用方约定

#### 2. 鉴权是全局的，不是按用户的

Gateway 当前使用的是全局 token / password 鉴权。

这意味着：

- 拿到凭证的人，本质上就是“进入整个运行时”
- 不是“某个用户登录后只能访问自己的会话”
- 更接近单控制台/单运维入口，而不是业务用户入口

#### 3. OpenAI HTTP 入口的用户语义不可信

HTTP 接口当前会把客户端传来的 `user` 字段或 `X-Session-Key` 直接用于生成会话键。

这意味着：

- 用户身份来自客户端自报
- 会话命名缺少服务端签发约束
- 恶意调用方可以伪造别人的 `sessionKey`
- 无法直接作为真实多用户入口暴露给业务方

#### 4. Session 查询和管理接口默认是全局视角

像 `sessions.list` 这类接口当前直接返回运行时中的全部 session，没有按用户或租户过滤。

这意味着：

- 一个连接拿到权限后，看到的是全局会话面
- 接口语义偏“运维控制台”
- 不适合直接给多用户前台使用

#### 5. 状态目录、会话存储、工作目录默认共享

默认状态目录和 workspace 都落在共享的 `~/.openclaw` 下。

这意味着：

- 不同用户会共享底层状态根目录
- 默认工作区不是按用户隔离
- transcript、session store、缓存、配置更像单实例共享资源

#### 6. 并发写入隔离还不够严谨

项目里已经有 session 文件锁能力，但 transcript 写入链路当前没有看到统一使用该锁。

这意味着：

- 同一会话高并发请求下可能出现 transcript 竞争写入
- 多 worker / 多线程 / 多实例情况下更容易出现顺序或内容问题

### 2.3 关键代码证据

下面这些代码位置直接支撑上述判断：

- `openclaw-app/src/main/java/com/openclaw/app/openai/OpenAiChatController.java`
  - `resolveSessionKey()` 优先信任 `X-Session-Key`，否则直接使用客户端传入的 `user` 和 `model` 拼接 `sessionKey`
  - 这说明 HTTP 入口的会话身份目前不是服务端签发的可信身份
- `openclaw-gateway/src/main/java/com/openclaw/gateway/auth/AuthService.java`
  - `resolveAuth()` / `authorize()` 读取的是全局 `gateway.auth.token/password`
  - 这说明网关当前是“单入口凭证”模型，而不是“每个用户独立登录态”模型
- `openclaw-gateway/src/main/java/com/openclaw/gateway/methods/CoreMethodRegistrar.java`
  - `handleSessionList()` 直接遍历 `sessionStore.listSessions()`
  - 这说明 session 查询接口默认是全局视角，不带主体过滤
- `openclaw-common/src/main/java/com/openclaw/common/config/ConfigPaths.java`
  - `resolveStateDir()` 默认落到共享 `~/.openclaw`
- `openclaw-common/src/main/java/com/openclaw/common/config/SessionPaths.java`
  - session store 与 transcript 默认都挂在共享 state root 下的 `agents/{agentId}/sessions/`
- `openclaw-agent/src/main/java/com/openclaw/agent/runtime/WorkspaceManager.java`
  - `resolveDefaultAgentWorkspaceDir()` 默认使用共享 `~/.openclaw/workspace`
- `openclaw-gateway/src/main/java/com/openclaw/gateway/session/TranscriptStore.java`
  - `appendEntry()` 直接 `APPEND` 写 JSONL，没有在这里统一接入 `SessionWriteLock`
  - 这说明 transcript 写入的并发一致性目前仍依赖外层约束，而不是内层强保证

## 3. 结论

### 3.1 结论一句话

当前系统是“支持多会话的单实例 Agent 运行时”，不是“原生多用户平台”。

### 3.2 可行性判断

它可以作为多用户系统的执行内核，但不建议直接裸暴露给最终用户。

## 4. 设计目标

如果要基于当前服务构建多用户并发访问系统，建议目标明确为以下四件事：

- 身份隔离：不同用户/租户必须有稳定、可信的服务端身份
- 会话隔离：每个业务会话必须可归属、可追踪、可审计
- 资源隔离：workspace、状态目录、凭证、工具权限要有边界
- 访问隔离：用户只能访问自己有权访问的会话和运行结果

## 5. 推荐方案

## 5.1 总体建议

推荐采用：

- 外层增加用户与会话管理层
- 内层保留当前项目作为 Agent Runtime
- 同时对当前项目做少量“可隔离化”改造

也就是：

**不是纯外包一层，也不是直接把当前项目整体改造成完整多租户平台，而是“外层托管 + 内层补隔离能力”的混合方案。**

## 5.2 推荐架构

```text
                ┌──────────────────────────────────────┐
                │          Business API / BFF          │
                │ 用户登录 / 租户 / 会话 / 权限 / 审计 │
                └──────────────────────────────────────┘
                                   │
                                   │ 内部可信调用
                                   ▼
                ┌──────────────────────────────────────┐
                │         Session Orchestrator          │
                │ sessionKey签发 / workspace分配 / 路由 │
                └──────────────────────────────────────┘
                                   │
                                   ▼
                ┌──────────────────────────────────────┐
                │         OpenClaw Runtime             │
                │ Agent执行 / 工具调用 / 模型交互 / 流式 │
                └──────────────────────────────────────┘
                                   │
                                   ▼
                ┌──────────────────────────────────────┐
                │   Model Provider / Tools / Storage   │
                └──────────────────────────────────────┘
```

## 6. 为什么不建议只做“外层包装”

如果完全不改当前项目，只在外面包一层，会有几个明显问题：

- 内层仍然默认共享状态目录
- 内层仍然默认共享 workspace
- 内层 session 管理接口仍然是全局视角
- 客户端如果绕过外层，仍可能直接访问底层运行时
- 未来做租户级限流、配额、审计时，内层缺少可信主键

所以纯外层包装不够。

## 7. 为什么也不建议“一步到位直接重构成多租户内核”

如果直接对当前项目做大规模内核重构，会牵涉：

- 鉴权模型
- session 模型
- transcript / store / workspace 路径体系
- Gateway 接口语义
- 工具权限模型
- Provider 凭证存储
- 各种 channel 的 sender/session 归属

改动面很大，风险高，而且会把“业务用户系统”和“Agent 执行内核”耦死。

所以不建议第一步就这么做。

## 8. 推荐的职责边界

### 8.1 外层负责

- 用户登录与认证
- 租户体系
- 会话创建与业务归属
- 用户与会话访问控制
- 限流、配额、计费
- 审计日志
- 会话生命周期编排
- 给内层签发可信运行上下文

### 8.2 内层负责

- Agent 执行
- 模型调用
- 工具调用
- 流式输出
- 会话上下文装配
- transcript 持久化
- 当前这一轮运行的执行状态管理

## 9. 关键设计

### 9.1 可信会话键

不要再让客户端直接决定底层 `sessionKey`。

建议由外层统一签发，例如：

```text
tenant:{tenantId}:user:{userId}:conv:{conversationId}
```

或者：

```text
org:{orgId}:app:{appId}:session:{conversationId}
```

要求：

- 只能由服务端生成
- 对用户透明或半透明
- 可直接映射到审计、计费、限流、资源目录

### 9.2 运行上下文

建议把下面这些信息作为内层运行请求的标准上下文：

- `tenantId`
- `userId`
- `conversationId`
- `sessionKey`
- `workspaceDir`
- `stateRoot`
- `authProfileId`
- `toolPolicyId`
- `requestId`
- `traceId`

### 9.3 资源隔离策略

至少要做到以下之一：

- 每租户独立 stateRoot
- 每用户独立 workspaceRoot
- 每会话独立 workspaceDir

推荐初期采用：

- `stateRoot` 按租户隔离
- `workspaceDir` 按会话隔离

示例：

```text
/data/openclaw/tenants/{tenantId}/state
/data/openclaw/tenants/{tenantId}/workspaces/{conversationId}
```

### 9.4 访问控制

Gateway / HTTP / Session API 需要从“全局控制台接口”改成“带主体过滤”的接口。

典型要求：

- `sessions.list` 只能列出当前用户或当前租户可见会话
- `chat.history` 只能读取授权会话
- `session.patch/delete/reset` 只能操作授权会话
- 不能依靠客户端自传 `sessionKey` 直接越权

### 9.5 并发模型

建议区分两类并发：

- 不同会话并发：允许
- 同一会话并发：原则上串行，或明确采用版本化写入

初期最稳妥的策略：

- 同一 `sessionKey` 只允许一个 active run
- 额外请求进入队列、拒绝，或中断前一轮

这比一开始就支持同会话并发读写 transcript 更稳。

## 10. 对当前项目的最小必要改造

建议优先改这些点。

### 10.1 不再信任外部传入的 `user` / `X-Session-Key`

HTTP / Gateway 入口只接受服务端签发后的内部会话标识。

### 10.2 `stateDir` 与 `workspaceDir` 可注入

不要把状态目录和 workspace 固定在共享默认路径。

需要支持：

- 每次请求指定运行上下文
- 每个租户/用户/会话映射不同目录

### 10.3 Session API 增加主体过滤

所有 session 查询、读取、修改接口，都需要带上调用主体上下文，并在服务端过滤。

### 10.4 Transcript 写入加锁

同一 transcript 文件的写入应统一走文件锁或串行队列。

### 10.5 Provider/Auth/Tool 权限隔离

至少要支持：

- 每租户不同 provider 凭证
- 每租户不同工具白名单
- 每租户不同沙箱/执行权限

### 10.6 建议的模块级改造映射

建议优先按下面的模块边界动手，而不是做一轮“大重构”：

| 模块 | 当前问题 | 最小改造建议 |
|------|---------|-------------|
| `OpenAiChatController` | 客户端可直接影响 `sessionKey` | 移除对外部 `user` / `X-Session-Key` 的直接信任，只接受外层签发的内部上下文 |
| `AuthService` | 只有全局 token/password | 保留运维入口鉴权，同时新增业务入口的用户态鉴权，不要复用同一语义 |
| `CoreMethodRegistrar` | session 查询/操作默认全局视角 | 给 `sessions.list/get/patch/delete/reset` 增加主体过滤 |
| `TranscriptStore` | 写入链路未统一加锁 | 对同一 transcript 强制文件锁或单 session 串行队列 |
| `SessionStore` | 只有 session 维度，没有 owner/principal 维度 | 给会话增加 `tenantId/userId/principalId` 等归属信息 |
| `WorkspaceManager` | 默认 workspace 共享 | 支持按 tenant/user/conversation 注入独立 `workspaceDir` |
| `ConfigPaths` / `SessionPaths` | 默认共享 state root | 支持外部注入 `stateRoot`，按租户或环境物理隔离 |
| Gateway / BFF 调用链 | 上下文缺少统一标准 | 建立统一 `RuntimeContext`：`tenantId/userId/conversationId/sessionKey/workspaceDir/stateRoot/toolPolicyId` |

## 11. 分阶段实施路径

## Phase 1：外层接管用户体系

目标：

- 不改动太多内核逻辑，先把真实用户入口收口到外层

工作项：

- 增加业务 API / BFF
- 接入登录态和租户
- 创建业务会话表
- 由外层生成内部 `sessionKey`
- 禁止前端直接调用底层 OpenClaw 接口

## Phase 2：内层补齐隔离能力

目标：

- 让 OpenClaw 从“共享单实例运行时”变成“可托管执行引擎”

工作项：

- 支持注入 `stateRoot`
- 支持注入 `workspaceDir`
- Session API 按主体过滤
- transcript 写入加锁
- 会话 active run 串行化

## Phase 3：租户级资源治理

目标：

- 能上线到真实并发环境

工作项：

- 租户级限流
- 配额与计费
- 审计日志
- 运行指标
- 失败重试与补偿
- 租户级 provider 配置

## Phase 4：再考虑共享运行时优化

目标：

- 在隔离已经可靠的前提下，再做性能与成本优化

工作项：

- worker 池
- 热会话缓存
- transcript 分层存储
- 多 runtime 水平扩展
- 调度与粘性路由

## 12. 直接改项目 vs 外层套会话&用户管理

### 方案 A：直接改造当前项目

优点：

- 逻辑集中
- 能做到深度内核一致性

缺点：

- 改动面非常大
- 风险高
- 周期长
- 会把业务用户系统与 Agent 运行时强耦合

结论：

不建议作为第一步。

### 方案 B：只在外层套一层会话与用户管理

优点：

- 上线快
- 对现有项目侵入小

缺点：

- 内层共享状态问题仍在
- 会话和资源隔离不彻底
- 存在被绕过和越权风险

结论：

也不建议纯这么做。

### 方案 C：外层管理 + 内层最小改造

优点：

- 改动可控
- 风险最低
- 可分阶段上线
- 长期演进空间最好

结论：

**推荐采用。**

## 13. 最终建议

### 建议一句话

把当前项目定位成“可隔离的 Agent Runtime”，在外层建设真正的用户、租户、会话、权限体系。

### 落地原则

- 用户身份在外层确权
- 会话键由外层签发
- 状态和工作区在内层做物理隔离
- 同一会话先串行，再考虑更复杂并发
- 所有 session API 都按主体过滤

## 14. 适合立即执行的下一步

建议按下面顺序开始：

1. 先定义外层会话表和 `sessionKey` 规范
2. 给当前项目增加可注入的 `stateRoot/workspaceDir/principal`
3. 收口 HTTP / Gateway 入口，不再信任客户端自定义 `sessionKey`
4. 给 transcript 写入补锁
5. 给 `sessions.list/history/patch/delete` 增加主体过滤

如果后续要继续实施，下一份文档建议直接进入“接口与表结构设计”，包括：

- 会话表
- 运行任务表
- 租户配置表
- provider 凭证表
- OpenClaw 调用协议
