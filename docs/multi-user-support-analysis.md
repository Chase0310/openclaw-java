# OpenClaw Java 多用户支持能力分析与设计方案

**文档版本**: 1.0
**分析日期**: 2026-04-01
**项目版本**: Phase 51 / 0.1.0-SNAPSHOT

---

## 执行摘要

**结论：当前系统部分支持多用户并发访问，但不支持传统的多租户架构。**

### 核心发现

| 维度 | 当前状态 | 支持程度 |
|------|---------|---------|
| 并发会话管理 | ✅ 支持 | 高 |
| 用户认证 | ❌ 无传统用户认证 | 无 |
| 多租户隔离 | ❌ 单租户设计 | 无 |
| 数据隔离 | ✅ 会话级隔离 | 中 |
| 访问控制 | ✅ 白名单/角色授权 | 中 |

### 推荐方案

**混合方案**：在外层添加用户认证与授权层，内部利用现有会话管理能力。

- **外层（新增）**：用户认证（JWT）、租户管理、权限系统
- **内层（现有）**：保持现有会话管理架构，注入租户上下文

---

## 一、当前系统多用户支持能力评估

### 1.1 用户认证机制

#### 现状：无传统用户认证

**❌ 不存在的功能：**
- 用户注册/登录系统
- 用户账号管理（User实体）
- HTTP Session管理
- JWT Token用户认证
- Spring Security集成

**✅ 存在的认证机制：**

##### 1.1.1 Gateway服务级认证

**文件位置**: `openclaw-gateway/src/main/java/com/openclaw/gateway/auth/AuthService.java`

```java
// 认证模式：token 或 password
// 环境变量：OPENCLAW_GATEWAY_TOKEN 或 OPENCLAW_GATEWAY_PASSWORD

认证特性：
- Token/Password 双模式
- 本地直连绕过认证
- 速率限制（默认5次失败 → 10秒冷却）
- Timing-safe字符串比较（防时序攻击）
- 失败追踪：ConcurrentHashMap<String, FailureRecord>
```

**适用场景**: WebSocket客户端连接认证，服务间通信

##### 1.1.2 外部服务认证

**文件位置**: `openclaw-agent/src/main/java/com/openclaw/agent/models/ModelAuth.java`

```java
LLM提供商API Key管理：
- Anthropic, OpenAI, GitHub Copilot, Qwen Portal
- 环境变量配置
- AWS SDK认证（Bedrock）
```

**结论**: 现有认证仅用于服务间通信和外部API调用，不涉及最终用户身份认证。

### 1.2 授权机制

#### 现状：基于角色的方法级授权

##### 1.2.1 WebSocket方法授权

**文件位置**: `openclaw-gateway/src/main/java/com/openclaw/gateway/websocket/MethodAuthorizer.java`

```java
// 角色分类
- operator: 人类用户
- node: 移动/桌面节点

// 权限范围
- operator.admin: 全部权限
- operator.read: 只读方法（status, logs, models list等）
- operator.write: 写操作（send, chat, browser control等）
- operator.approvals: 审批流程
- operator.pairing: 设备配对操作

// 方法分类
READ_METHODS: 30+ 方法
WRITE_METHODS: 40+ 方法
ADMIN_ONLY_METHODS: 管理员专用
```

##### 1.2.2 命令执行授权

**文件位置**:
- `openclaw-app/src/main/java/com/openclaw/app/commands/CommandAuthorization.java`
- `openclaw-autoreply/src/main/java/com/openclaw/autoreply/CommandAuth.java`

```java
授权机制：白名单
- commands.ownerAllowFrom（命令配置）
- channels.telegram.*.allowFrom（渠道配置）
- 两个来源合并

授权规则：
- 空列表 → 所有人授权
- 通配符 "*" → 所有人授权
- 否则 → 发送者必须在白名单中
```

##### 1.2.3 渠道访问控制

**文件位置**: `openclaw-channel/src/main/java/com/openclaw/channel/telegram/TelegramBotAccess.java`

```java
Telegram访问控制：
- DM策略: open / disabled / allowlist
- 群组策略: open / disabled / allowlist
- per-group allowFrom覆盖（细粒度控制）
- Username/ID匹配（provider-specific normalization）
```

**结论**: 现有授权基于渠道发送者ID（如Telegram userId），非用户身份。

### 1.3 会话管理与并发处理

#### 1.3.1 会话模型

**文件位置**: `openclaw-common/src/main/java/com/openclaw/common/model/AcpSession.java`

```java
会话字段：
- sessionId: UUID标识符
- sessionKey: 派生自channel+accountId+peer（隔离键）
- cwd: 当前工作目录
- createdAt / updatedAt: 时间戳
- activeRunId: 当前活跃对话运行
- kind: "direct" | "group" | "global"
- channel: 渠道提供者（telegram, discord, wechat）
- agentId: 使用的Agent ID
- model / modelProvider: LLM配置
- inputTokens / outputTokens / totalTokens: Token使用量
- sessionFile: JSONL记录文件路径

线程安全：
- volatile boolean cancelled: 线程安全的取消标志
```

#### 1.3.2 会话存储

**文件位置**: `openclaw-gateway/src/main/java/com/openclaw/gateway/session/SessionStore.java`

```java
内存存储（线程安全）：
- ConcurrentHashMap<String, AcpSession>: 主会话存储
- ConcurrentHashMap<String, String> runToSession: 运行ID到会话ID映射

操作：
- createSession(): 创建新会话
- getSession(): 通过ID获取会话
- findBySessionKey(): 通过sessionKey查找
- startRun() / endRun() / cancelRun(): 会话生命周期
```

**会话隔离**:
- 每个会话有唯一的 `sessionId` 和 `sessionKey`
- 支持多用户并发访问
- 会话按Agent ID分区：`~/.openclaw/agents/{agentId}/sessions/`

#### 1.3.3 WebSocket连接管理

**文件位置**: `openclaw-gateway/src/main/java/com/openclaw/gateway/websocket/GatewayConnection.java`

```java
连接状态管理：
- AtomicLong requestCounter: 线程安全的请求ID生成
- ConcurrentHashMap<String, CompletableFuture<JsonNode>>: 异步响应处理
- 握手状态机: PENDING → CONNECTED/FAILED

连接存储：
- connectParams: 连接参数
- role: operator/node
- scopes: 权限范围
- authMethod: 认证方式
- clientIp: 客户端IP
```

#### 1.3.4 并发处理模式

**线程安全集合（373处使用，118个文件）**:
- `ConcurrentHashMap`: 所有共享可变状态
- `ConcurrentHashMap.newKeySet()`: 线程安全集合
- `CopyOnWriteArraySet`: 客户端注册表
- `AtomicBoolean`, `AtomicReference`, `AtomicLong`: 原子状态

**异步处理**:
- `CompletableFuture`: 非阻塞操作
- WebSocket处理委托到`CompletableFuture`链
- SSE流式传输使用原子状态标志

**无ThreadLocal用户上下文**:
- 显式传递sessionId/sessionKey
- 所有用户状态通过参数传递

### 1.4 数据持久化架构

#### 1.4.1 文件系统存储（无数据库）

**会话元数据**: `~/.openclaw/state/agents/{agentId}/sessions/sessions.json`

```json
{
  "sessionKey": {
    "sessionId": "uuid",
    "sessionKey": "telegram:botid:userid:direct",
    "sessionFile": "/path/to/session.jsonl",
    "cwd": "/working/dir",
    "createdAt": "2026-04-01T10:00:00Z",
    "updatedAt": "2026-04-01T10:30:00Z"
  }
}
```

**对话记录**: `~/.openclaw/sessions/{sessionId}.jsonl`

```jsonl
{"type":"session","version":1,"id":"...","timestamp":"..."}
{"message":{"role":"user","content":"Hello"}}
{"message":{"role":"assistant","content":"Hi there!"}}
```

**使用量追踪**: `{sessionId}-usage.jsonl`

```jsonl
{"timestamp":"...","model":"claude-sonnet-4-5","inputTokens":100,"outputTokens":50}
```

#### 1.4.2 文件锁机制

**文件位置**: `openclaw-agent/src/main/java/com/openclaw/agent/session/SessionWriteLock.java`

```java
功能：
- 防止并发写入同一会话文件
- FileLock + PID追踪 + 僵死检测（默认30分钟）
- 可重入锁 + 引用计数
- ConcurrentHashMap<String, HeldLock>: 锁追踪
- JVM关闭钩子清理锁
```

**结论**: 文件存储支持单实例多用户，但跨实例部署需要共享存储。

### 1.5 数据模型分析

#### 1.5.1 无传统数据库实体

**关键发现**:
- ❌ 无@Entity或@Table注解
- ❌ 无JPA/Hibernate
- ❌ 无Repository/DAO接口
- ❌ 无传统数据库表

**数据结构**:
- POJO（Plain Old Java Objects）
- Lombok注解（@Data, @Builder）
- JSON配置驱动

#### 1.5.2 核心实体

**AcpSession**: 唯一会话实体
**OpenClawConfig**: 系统配置（90+嵌套类型）
**ChannelTypes**: 渠道类型定义

**无用户关联**:
- ❌ 无User实体
- ❌ 无tenant_id字段
- ❌ 无organization_id字段

#### 1.5.3 现有隔离机制

**Agent级隔离**:
- 每个Agent独立目录：`state/agents/{agentId}/`
- 不同Agent的会话互不干扰

**会话级隔离**:
- sessionKey = channel + accountId + peer + chatType
- 不同用户会话完全隔离

**渠道账号隔离**:
- 支持每渠道多账号（如多个Telegram机器人）

**结论**: 系统设计为单租户多用户，无多租户概念。

---

## 二、多用户并发访问系统设计方案

### 2.1 架构决策：混合方案

#### 方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **A. 直接改造内部** | 深度集成，性能最优 | 改动量大，风险高，破坏现有架构 | ⭐⭐ |
| **B. 外层代理层** | 改动小，解耦，易回滚 | 增加一层，性能损耗 | ⭐⭐⭐⭐ |
| **C. 混合方案** | 平衡改动和集成度 | 需要上下文注入机制 | ⭐⭐⭐⭐⭐ |

#### 推荐方案：混合方案

**核心思想**：在外层添加用户认证与租户管理，内部利用现有会话能力。

```
┌─────────────────────────────────────────────────────────────┐
│                   用户认证与租户管理层（新增）           │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐  │
│  │ 用户认证 │ │ 租户管理 │ │ 权限系统 │ │ 审计日志 │  │
│  │ (JWT)   │ │ (Tenant) │ │ (RBAC)   │ │          │  │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘  │
└──────────────────────────┬──────────────────────────────┘
                       │ HTTP/WebSocket
                       ↓ 传递 userId + tenantId
┌─────────────────────────────────────────────────────────────┐
│              OpenClaw Gateway（改造注入上下文）            │
│  ┌───────────────────────────────────────────────────┐    │
│  │ TenantContext (租户上下文 - 新增)               │    │
│  │  - ThreadLocal<UserId>                          │    │
│  │  - ThreadLocal<TenantId>                        │    │
│  │  - resolve() : 会话Key = tenantId + channel...   │    │
│  └───────────────────────────────────────────────────┘    │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐             │
│  │ Session │ │  Agent  │ │ Channel │             │
│  │ Store   │ │ Runtime │ │ Adapter │             │
│  └─────────┘ └─────────┘ └─────────┘             │
└─────────────────────────────────────────────────────────────┘
                       │
                       ↓
┌─────────────────────────────────────────────────────────────┐
│                   持久化层（改造分区）                 │
│  ~/.openclaw/tenants/{tenantId}/agents/...            │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 详细设计

#### 2.2.1 用户认证层（新增）

**模块**: `openclaw-auth`（新建）

**依赖**:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
</dependency>
```

**核心类**:

##### 1. User实体

```java
@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastLoginAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles;
}
```

##### 2. Tenant实体

```java
@Entity
@Table(name = "tenants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID tenantId;

    @Column(nullable = false, unique = true)
    private String tenantName;

    @Column(nullable = false)
    private String tenantSlug;

    @Column(nullable = false)
    private String apiKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean isActive;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL)
    private List<User> users;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL)
    private List<AgentConfig> agents;
}
```

##### 3. Role实体

```java
@Entity
@Table(name = "roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String roleName;

    private String description;

    @ManyToMany(mappedBy = "roles")
    private Set<User> users;

    @ManyToMany(mappedBy = "roles")
    private Set<Permission> permissions;
}
```

##### 4. JWT认证服务

```java
@Service
public class JwtAuthService {

    @Value("${auth.jwt.secret}")
    private String jwtSecret;

    @Value("${auth.jwt.expiration}")
    private long jwtExpiration;

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getUserId());
        claims.put("tenantId", user.getTenantId());
        claims.put("roles", user.getRoles().stream()
            .map(Role::getRoleName).collect(Collectors.toList()));

        return Jwts.builder()
            .setClaims(claims)
            .setSubject(user.getUsername())
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(SignatureAlgorithm.HS512, jwtSecret)
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .setSigningKey(jwtSecret)
            .parseClaimsJws(token)
            .getBody();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
```

##### 5. Spring Security配置

```java
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/auth/**").permitAll()
                .antMatchers("/ws/**").authenticated()
                .anyRequest().authenticated()
            .and()
            .addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

##### 6. JWT过滤器

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtAuthService jwtAuthService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && jwtAuthService.validateToken(token)) {
            Claims claims = jwtAuthService.parseToken(token);

            UserId userId = UUID.fromString(claims.get("userId").toString());
            TenantId tenantId = UUID.fromString(claims.get("tenantId").toString());

            // 设置认证信息
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    new UserPrincipal(userId, tenantId),
                    null,
                    getAuthorities(claims));

            SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

##### 7. UserPrincipal

```java
@Data
@AllArgsConstructor
public class UserPrincipal implements UserDetails {

    private UUID userId;
    private UUID tenantId;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
   public String getUsername() {
        return userId.toString();
    }
}
```

**API端点**:

```
POST   /api/auth/register    # 用户注册
POST   /api/auth/login       # 用户登录（返回JWT）
POST   /api/auth/refresh    # 刷新Token
GET    /api/auth/logout      # 登出（服务端黑名单）
GET    /api/auth/profile     # 获取当前用户信息
```

#### 2.2.2 租户上下文注入（改造现有模块）

**模块**: `openclaw-common`（改造）

**新建类**: TenantContext

```java
package com.openclaw.common.context;

import java.util.UUID;

/**
 * 租户上下文 - 存储当前请求的租户和用户信息
 */
public class TenantContext {

    private static final ThreadLocal<UUID> tenantId = new ThreadLocal<>();
    private static final ThreadLocal<UUID> userId = new ThreadLocal<>();

    public static void setTenantId(UUID id) {
        tenantId.set(id);
    }

    public static UUID getTenantId() {
        return tenantId.get();
    }

    public static void setUserId(UUID id) {
        userId.set(id);
    }

    public static UUID getUserId() {
        return userId.get();
    }

    public static void clear() {
        tenantId.remove();
        userId.remove();
    }
}
```

**改造**: SessionPaths

```java
public class SessionPaths {

    public static Path getAgentsDir(UUID tenantId) {
        return Paths.get(System.getProperty("user.home"),
            ".openclaw", "tenants", tenantId.toString(), "state", "agents");
    }

    public static Path getSessionsDir(UUID tenantId, String agentId) {
        return getAgentsDir(tenantId).resolve(agentId).resolve("sessions");
    }

    public static Path getSessionFile(UUID tenantId, String agentId, String sessionId) {
        return getSessionsDir(tenantId, agentId).resolve(sessionId + ".jsonl");
    }

    public static Path getMetadataFile(UUID tenantId, String agentId) {
        return getSessionsDir(tenantId, agentId).resolve("sessions.json");
    }
}
```

**改造**: SessionStore

```java
@Service
public class SessionStore {

    private final ConcurrentHashMap<String, AcpSession> sessions;

    // 租户感知的sessionKey生成
    public static String generateSessionKey(
            UUID tenantId,
            String channel,
            String accountId,
            String peerId,
            String chatType) {

        return String.format("%s:%s:%s:%s:%s:%s",
            tenantId.toString(),
            channel,
            accountId,
            peerId,
            chatType);
    }

    public AcpSession createSession(CreateSessionParams params) {
        UUID tenantId = TenantContext.getTenantId();

        String sessionKey = generateSessionKey(
            tenantId,
            params.getChannel(),
            params.getAccountId(),
            params.getPeerId(),
            params.getChatType());

        AcpSession session = AcpSession.builder()
            .sessionId(UUID.randomUUID())
            .sessionKey(sessionKey)
            .tenantId(tenantId)  // 新增字段
            .userId(TenantContext.getUserId())  // 新增字段
            // ... 其他字段
            .build();

        sessions.put(session.getSessionId(), session);
        return session;
    }
}
```

**改造**: AcpSession

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcpSession {
    private UUID sessionId;
    private String sessionKey;

    // 新增租户和用户字段
    private UUID tenantId;
    private UUID userId;

    // ... 现有字段
}
```

**改造**: GatewayWebSocketHandler

```java
@Component
public class GatewayWebSocketHandler {

    @Autowired
    private TenantContext tenantContext;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 从认证过滤器获取用户信息
        Authentication auth = SecurityContextHolder.getContext()
            .getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
            UserPrincipal principal = (UserPrincipal) auth.getPrincipal();

            // 设置租户上下文
            TenantContext.setTenantId(principal.getTenantId());
            TenantContext.setUserId(principal.getUserId());

            // 创建连接
            GatewayConnection connection = new GatewayConnection(
                session,
                principal.getTenantId(),
                principal.getUserId());

            // ... 继续连接处理
        }

        try {
            super.afterConnectionEstablished(session);
        } finally {
            // 清理上下文（在请求结束时）
            TenantContext.clear();
        }
    }
}
```

**改造**: OpenAiChatController

```java
@RestController
@RequestMapping("/v1")
public class OpenAiChatController {

    @Autowired
    private TenantContext tenantContext;

    @PostMapping("/chat/completions")
    public SseEmitter chatCompletions(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Session-Key", required = false) String sessionKeyHeader) {

        // 获取租户上下文
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // 租户感知的sessionKey
        String tenantAwareSessionKey = sessionKeyHeader != null
            ? sessionKeyHeader
            : SessionStore.generateSessionKey(
                tenantId,
                "api",
                "default",
                userId.toString(),
                "direct");

        // ... 继续处理
    }
}
```

#### 2.2.3 权限系统（新增）

**模块**: `openclaw-auth`（扩展）

##### 1. Permission实体

```java
@Entity
@Table(name = "permissions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String permissionName;

    private String description;

    @ManyToMany(mappedBy = "permissions")
    private Set<Role> roles;
}
```

##### 2. 权限注解

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasPermission(#tenantId, 'TENANT_READ')")
public @interface TenantReadPermission {
}

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasPermission(#tenantId, 'TENANT_WRITE')")
public @interface TenantWritePermission {
}
```

##### 3. 自定义权限评估器

```java
@Component
public class CustomPermissionEvaluator implements PermissionEvaluator {

    @Override
    public boolean hasPermission(
            Authentication authentication,
            Object targetId,
            Object permission) {

        if (!(authentication.getPrincipal() instanceof UserPrincipal)) {
            return false;
        }

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        // 检查租户访问权限
        if (targetId instanceof UUID) {
            UUID targetTenantId = (UUID) targetId;
            return targetTenantId.equals(principal.getTenantId());
        }

        return false;
    }
}
```

#### 2.2.4 审计日志（新增）

**模块**: `openclaw-common`（扩展）

##### 1. Audit实体

```java
@Entity
@Table(name = "audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID auditId;

    private UUID tenantId;
    private UUID userId;
    private String action;
    private String resource;
    private String resourceId;
    private LocalDateTime timestamp;
    private String ipAddress;
    private String userAgent;
}
```

##### 2. 审计切面

```java
@Aspect
@Component
public class AuditAspect {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @AfterReturning(
        pointcut = "@annotation(auditable)",
        returning = "result")
    public void auditAction(
            JoinPoint joinPoint,
            Auditable auditable) {

        Authentication auth = SecurityContextHolder.getContext()
            .getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
            UserPrincipal principal = (UserPrincipal) auth.getPrincipal();

            AuditLog auditLog = AuditLog.builder()
                .auditId(UUID.randomUUID())
                .tenantId(principal.getTenantId())
                .userId(principal.getUserId())
                .action(auditable.action())
                .resource(auditable.resource())
                .timestamp(LocalDateTime.now())
                .ipAddress(getClientIp())
                .userAgent(getUserAgent())
                .build();

            auditLogRepository.save(auditLog);
        }
    }
}
```

##### 3. Auditable注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action();
    String resource();
}
```

### 2.3 持久化层改造

#### 2.3.1 数据库设计

**新增依赖**:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

**迁移脚本**: `V1__init_schema.sql`

```sql
-- 租户表
CREATE TABLE tenants (
    tenant_id UUID PRIMARY KEY,
    tenant_name VARCHAR(255) NOT NULL,
    tenant_slug VARCHAR(255) UNIQUE NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true
);

-- 用户表
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    tenant_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP,
    FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id)
);

-- 角色表
CREATE TABLE roles (
    id SERIAL PRIMARY KEY,
    role_name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT
);

-- 权限表
CREATE TABLE permissions (
    id SERIAL PRIMARY KEY,
    permission_name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT
);

-- 用户角色关联
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id INTEGER NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- 角色权限关联
CREATE TABLE role_permissions (
    role_id INTEGER NOT NULL,
    permission_id INTEGER NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

-- 审计日志
CREATE TABLE audit_logs (
    audit_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID,
    action VARCHAR(255) NOT NULL,
    resource VARCHAR(255),
    resource_id VARCHAR(255),
    timestamp TIMESTAMP NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    FOREIGN KEY (tenant_id) REFERENCES tenants(tenant_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 索引
CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_audit_logs_tenant ON audit_logs(tenant_id);
CREATE INDEX idx_audit_logs_timestamp ON audit_logs(timestamp);
```

#### 2.3.2 文件存储分区

**目录结构**:

```
~/.openclaw/
├── tenants/                          # 租户分区（新增）
│   ├── {tenantId}/
│   │   ├── config.json              # 租户配置
│   │   ├── state/
│   │   │   └── agents/
│   │   │       ├── {agentId}/
│   │   │       │   └── sessions/
│   │   │       │       ├── sessions.json
│   │   │       │       ├── {sessionId}.jsonl
│   │   │       │       └── {sessionId}-usage.jsonl
│   │   │       └── ...
│   │   └── ...
├── shared/                           # 共享资源
│   ├── plugins/
│   └── skills/
└── config.json                       # 默认配置
```

**迁移逻辑**:

```java
@Component
public class TenantMigrationService {

    @Autowired
    private FileMigrationUtil fileMigrationUtil;

    public void migrateToMultiTenant() {
        Path oldAgentsDir = Paths.get(System.getProperty("user.home"),
            ".openclaw", "state", "agents");

        Path tenantsDir = Paths.get(System.getProperty("user.home"),
            ".openclaw", "tenants");

        // 创建默认租户
        UUID defaultTenantId = UUID.randomUUID();

        // 迁移现有Agent数据
        Files.list(oldAgentsDir)
            .forEach(agentDir -> {
                Path tenantAgentDir = tenantsDir
                    .resolve(defaultTenantId.toString())
                    .resolve("state")
                    .resolve("agents")
                    .resolve(agentDir.getFileName());

                fileMigrationUtil.moveDirectory(agentDir, tenantAgentDir);
            });
    }
}
```

### 2.4 数据库Repository层

```java
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByTenantId(UUID tenantId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByTenantSlug(String slug);

    List<Tenant> findByIsActiveTrue();

    boolean existsByTenantSlug(String slug);
}

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByRoleName(String roleName);

    Set<Role> findByUsers_UserId(UUID userId);
}

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByPermissionName(String name);

    Set<Permission> findByRoles_Id(Long roleId);
}

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByTenantIdOrderByTimestampDesc(
        UUID tenantId, Pageable pageable);

    List<AuditLog> findByUserIdOrderByTimestampDesc(
        UUID userId, Pageable pageable);
}
```

### 2.5 配置管理

**application.yml**:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/openclaw
    username: openclaw
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration

auth:
  jwt:
    secret: ${JWT_SECRET:your-256-bit-secret-key-here}
    expiration: 86400000  # 24小时

openclaw:
  multi-tenant: true
  default-tenant-id: ${DEFAULT_TENANT_ID}
```

### 2.6 API示例

#### 2.6.1 用户注册

```http
POST /api/auth/register
Content-Type: application/json

{
  "username": "john.doe",
  "password": "SecurePass123!",
  "email": "john@example.com",
  "tenantSlug": "my-company"
}

Response 201:
{
  "userId": "uuid",
  "username": "john.doe",
  "email": "john@example.com",
  "tenantId": "uuid",
  "tenantSlug": "my-company",
  "roles": ["USER"]
}
```

#### 2.6.2 用户登录

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "john.doe",
  "password": "SecurePass123!"
}

Response 200:
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "type": "Bearer",
  "expiresIn": 86400,
  "user": {
    "userId": "uuid",
    "username": "john.doe",
    "tenantId": "uuid",
    "roles": ["USER"]
  }
}
```

#### 2.6.3 受保护的API调用

```http
GET /api/v1/agents
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...

Response 200:
{
  "agents": [
    {
      "id": "default",
      "name": "Default Agent",
      "model": "anthropic/claude-sonnet-4-5"
    }
  ]
}
```

### 2.7 安全性增强

#### 2.7.1 密码策略

```java
@Component
public class PasswordValidator {

    private static final Pattern PASSWORD_PATTERN =
        Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    public boolean isValid(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        return PASSWORD_PATTERN.matcher(password).matcher().matches();
    }

    public String getRequirements() {
        return "密码必须至少8位，包含大小写字母和数字";
    }
}
```

#### 2.7.2 Token刷新

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private JwtAuthService jwtAuthService;

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.substring(7); // Remove "Bearer "
        Claims claims = jwtAuthService.parseToken(token);

        UUID userId = UUID.fromString(claims.get("userId").toString());
        // ... 验证用户有效性

        String newToken = jwtAuthService.generateToken(user);

        return ResponseEntity.ok(newToken);
    }
}
```

#### 2.7.3 速率限制

```java
@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> requestCounts =
        new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 60000)  // 每分钟重置
    public void resetCounts() {
        requestCounts.clear();
    }

    public boolean allowRequest(String userId) {
        AtomicInteger count = requestCounts.computeIfAbsent(
            userId, k -> new AtomicInteger(0));

        return count.incrementAndGet() <= 60;  // 每分钟60次
    }
}
```

---

## 三、实施计划

### 3.1 阶段划分

#### 阶段1: 基础架构搭建（2-3周）

**任务**:
1. 创建 `openclaw-auth` 模块
2. 设计并实现用户/租户/角色/权限实体
3. 配置PostgreSQL数据库和Flyway迁移
4. 实现JWT认证服务
5. 配置Spring Security

**交付物**:
- 可运行的用户认证系统
- 数据库Schema和初始迁移脚本
- 基础API端点（注册/登录/刷新）

#### 阶段2: 上下文注入（1-2周）

**任务**:
1. 创建 `TenantContext` 类
2. 改造 `AcpSession` 添加租户/用户字段
3. 改造 `SessionPaths` 支持租户分区
4. 改造 `SessionStore` 租户感知
5. 实现 `TenantContext` 在HTTP/WebSocket中的注入

**交付物**:
- 租户上下文框架
- 改造后的会话管理系统
- 单元测试覆盖

#### 阶段3: API集成（2-3周）

**任务**:
1. 改造 `GatewayWebSocketHandler` 集成认证
2. 改造 `OpenAiChatController` 租户感知
3. 改造所有Channel适配器
4. 实现权限注解和评估器
5. 添加审计日志系统

**交付物**:
- 完整的API集成
- 权限系统
- 审计日志功能

#### 阶段4: 持久化改造（1-2周）

**任务**:
1. 实现文件存储分区
2. 编写数据迁移工具
3. 测试数据迁移
4. 实现租户级配置加载

**交付物**:
- 迁移工具
- 迁移文档
- 测试报告

#### 阶段5: 测试与优化（2-3周）

**任务**:
1. 集成测试（多租户场景）
2. 性能测试（并发访问）
3. 安全测试（认证/授权）
4. 文档编写
5. 代码审查和优化

**交付物**:
- 测试报告
- 部署文档
- 用户文档
- API文档

### 3.2 技术风险评估

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| 破坏现有API兼容性 | 高 | 中 | 保留老版本API路径，渐进式迁移 |
| 数据迁移失败 | 高 | 低 | 完整备份，回滚脚本 |
| 性能下降 | 中 | 中 | 压力测试，性能基准测试 |
| 线程安全问题 | 高 | 低 | 严格代码审查，并发测试 |
| 租户数据泄露 | 极高 | 低 | 权限审计，渗透测试 |

### 3.3 回滚计划

**回滚触发条件**:
- 严重性能问题（响应时间增加 > 50%）
- 数据损坏或丢失
- 安全漏洞
- 用户无法登录

**回滚步骤**:
1. 停止新版本部署
2. 恢复数据库备份
3. 恢复文件系统备份
4. 重启旧版本服务
5. 通知用户服务降级

---

## 四、总结与建议

### 4.1 核心结论

1. **当前系统支持多用户并发访问**，但缺乏传统的用户认证和租户隔离
2. **推荐混合方案**：外层添加用户认证+租户管理，内部利用现有会话能力
3. **实施难度中等**：需要改造核心模块，但现有架构良好支持扩展

### 4.2 关键优势

- **线程安全设计**：现有系统已使用大量并发安全组件
- **会话隔离**：sessionKey机制天然支持多用户
- **模块化架构**：15个模块清晰分离，易于改造
- **文件存储灵活**：易于添加租户分区

### 4.3 注意事项

- **数据迁移**：需要 carefully 设计迁移策略，避免数据丢失
- **性能监控**：租户上下文注入可能带来性能开销
- **安全审计**：定期审计权限和租户隔离
- **向后兼容**：保留老版本API路径，平滑过渡

### 4.4 后续优化方向

1. **分布式会话存储**：使用Redis替代ConcurrentHashMap
2. **租户级资源配额**：防止资源滥用
3. **SSO集成**：支持OAuth2/OpenID Connect
4. **多租户监控**：每个租户的独立监控面板

---

## 附录

### A. 数据库Schema完整定义

见 `2.3.1` 节中的SQL脚本。

### B. API端点清单

#### 认证相关

| 方法 | 路径 | 描述 | 认证 |
|------|------|------|------|
| POST | /api/auth/register | 用户注册 | ❌ |
| POST | /api/auth/login | 用户登录 | ❌ |
| POST | /api/auth/refresh | 刷新Token | ✅ |
| GET | /api/auth/logout | 登出 | ✅ |
| GET | /api/auth/profile | 获取用户信息 | ✅ |

#### 租户管理

| 方法 | 路径 | 描述 | 认证 | 权限 |
|------|------|------|------|------|
| GET | /api/tenants | 获取租户列表 | ✅ | TENANT_READ |
| POST | /api/tenants | 创建租户 | ✅ | TENANT_ADMIN |
| GET | /api/tenants/{id} | 获取租户详情 | ✅ | TENANT_READ |
| PUT | /api/tenants/{id} | 更新租户 | ✅ | TENANT_WRITE |
| DELETE | /api/tenants/{id} | 删除租户 | ✅ | TENANT_ADMIN |

#### 用户管理

| 方法 | 路径 | 描述 | 认证 | 权限 |
|------|------|------|------|------|
| GET | /api/users | 获取用户列表 | ✅ | USER_READ |
| GET | /api/users/{id} | 获取用户详情 | ✅ | USER_READ |
| PUT | /api/users/{id} | 更新用户 | ✅ | USER_WRITE |
| DELETE | /api/users/{id} | 删除用户 | ✅ | USER_ADMIN |
| POST | /api/users/{id}/roles | 分配角色 | ✅ | USER_ADMIN |

#### Agent API（现有+租户感知）

| 方法 | 路径 | 描述 | 认证 |
|------|------|------|------|
| GET | /api/v1/agents | 获取Agent列表 | ✅ |
| POST | /api/v1/chat | 发送聊天消息 | ✅ |
| WebSocket | /ws | WebSocket连接 | ✅ |

### C. 依赖清单

```xml
<!-- 新增依赖 -->
<dependencies>
    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.11.5</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>

    <!-- JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Flyway -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>

    <!-- BCrypt -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-crypto</artifactId>
    </dependency>
</dependencies>
```

### D. 配置模板

```yaml
# application.yml
spring:
  application:
    name: openclaw-app

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:openclaw}
    username: ${DB_USERNAME:openclaw}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: ${SHOW_SQL:false}
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
    open-in-view: false

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

  jackson:
    serialization:
      write-dates-as-timestamps: false
    time-zone: UTC

# 认证配置
auth:
  jwt:
    secret: ${JWT_SECRET}
    expiration: ${JWT_EXPIRATION:86400000}  # 24小时
    refresh-expiration: ${REFRESH_EXPIRATION:604800000}  # 7天

# OpenClaw配置
openclaw:
  multi-tenant: true
  default-tenant-id: ${DEFAULT_TENANT_ID}
  config-path: ${OPENCLAW_CONFIG_PATH:~/.openclaw/config.json}
  state-path: ${OPENCLAW_STATE_PATH:~/.openclaw/state}

  # 租户配置
  tenants:
    base-path: ~/.openclaw/tenants
    max-sessions-per-user: 100
    session-timeout: 3600000  # 1小时

  # 速率限制
  rate-limit:
    enabled: true
    requests-per-minute: 60
    burst: 10

# 日志配置
logging:
  level:
    com.openclaw: ${LOG_LEVEL:INFO}
    org.springframework.security: ${SECURITY_LOG_LEVEL:WARN}
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"

# 监控配置
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### E. 测试用例示例

```java
@SpringBootTest
@AutoConfigureMockMvc
public class MultiTenantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    @Transactional
    public void testMultiTenantIsolation() throws Exception {
        // 创建租户1和租户2
        Tenant tenant1 = tenantRepository.save(Tenant.builder()
            .tenantName("Company A")
            .tenantSlug("company-a")
            .apiKey("key-a")
            .isActive(true)
            .createdAt(LocalDateTime.now())
            .build());

        Tenant tenant2 = tenantRepository.save(Tenant.builder()
            .tenantName("Company B")
            .tenantSlug("company-b")
            .apiKey("key-b")
            .isActive(true)
            .createdAt(LocalDateTime.now())
            .build());

        // 创建用户
        User user1 = userRepository.save(User.builder()
            .username("user-a")
            .passwordHash("$2a$10$...")  // BCrypt hash
            .email("user-a@company-a.com")
            .tenantId(tenant1.getTenantId())
            .createdAt(LocalDateTime.now())
            .build());

        User user2 = userRepository.save(User.builder()
            .username("user-b")
            .passwordHash("$2a$10$...")
            .email("user-b@company-b.com")
            .tenantId(tenant2.getTenantId())
            .createdAt(LocalDateTime.now())
            .build());

        // 租户1用户登录
        String token1 = loginUser("user-a", "password-a");

        // 租户1用户访问Agent
        mockMvc.perform(get("/api/v1/agents")
                .header("Authorization", "Bearer " + token1))
            .andExpect(status().isOk());

        // 租户2用户尝试访问租户1的数据
        mockMvc.perform(get("/api/tenants/" + tenant1.getTenantId())
                .header("Authorization", "Bearer " + token2))
            .andExpect(status().isForbidden());
    }

    private String loginUser(String username, String password) throws Exception {
        // ... 实现登录逻辑
        return "jwt-token";
    }
}
```

---

**文档结束**
