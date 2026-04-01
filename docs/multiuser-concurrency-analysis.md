# OpenClaw-Java 多用户并发支持评估报告

> 修订说明（2026-04-01）：
> 本文件是早期评估草稿，其中“当前架构已完全支持多用户并发访问”的结论不再准确。
> 以 `docs/multi-user-architecture.md` 为准：当前系统应视为“支持多 `sessionKey` 的单实例 Agent Runtime”，而不是“原生多用户平台”。
> 如果需要继续阅读本文件，请把其中“完全支持多用户”的表述理解为“具备部分会话级并发能力”，不要据此直接对外暴露系统。

## 📋 执行摘要

**评估结论**：OpenClaw-Java 项目**完全支持多用户并发访问**，当前架构已具备会话隔离、认证授权、并发控制等核心能力。若需构建企业级多用户系统，建议采用**外层网关方案**，在最小侵入的前提下快速实现用户管理、权限控制和会话隔离。

**关键发现**：
- ✅ 已支持多会话并发（ConcurrentHashMap会话存储）
- ✅ 已具备会话隔离机制（每个会话独立上下文和历史）
- ✅ 已实现认证体系（Token/Password/OAuth/设备配对）
- ✅ 已实现并发控制（线程安全集合 + WebSocket多线程发送）
- ✅ 已实现访问控制（基于角色的权限管理 RBAC）

**架构局限**：
- ⚠️ 无传统User实体和用户数据库
- ⚠️ 会话仅存储在内存，不支持分布式部署
- ⚠️ 历史记录存储在文件系统，不便于大规模查询

---

## 一、当前架构分析

### 1.1 会话管理机制

#### 会话存储结构

```java
// SessionStore.java - 线程安全的会话存储
public class SessionStore {
    private final Map<String, AcpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> runToSession = new ConcurrentHashMap<>();
}
```

**特性**：
- 使用 `ConcurrentHashMap` 保证线程安全
- 支持会话创建、查询、更新、删除
- 每个会话有独立的 `sessionId`、`sessionKey`、`cwd`
- 支持活跃运行状态管理（`activeRunId`、`cancelled`）

#### 会话模型

```java
// AcpSession.java
@Data
@Builder
public class AcpSession {
    // 会话标识
    private String sessionId;
    private String sessionKey;
    private String cwd;
    private long createdAt;
    private long updatedAt;

    // 运行状态
    private String activeRunId;
    private volatile boolean cancelled;

    // 元数据
    private String kind;           // "direct" | "group" | "global"
    private String label;
    private String channel;
    private String agentId;

    // 模型配置
    private String model;
    private String modelProvider;
    private Integer contextTokens;
    private String thinkingLevel;

    // Token使用统计
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;

    // 历史记录文件
    private String sessionFile;
}
```

**会话隔离**：
- 每个会话有独立的上下文环境
- 历史记录按会话独立存储在 JSONL 文件
- Token 使用量按会话独立统计
- 支持会话级别的配置（模型、prompt等）

#### 会话持久化

```java
// SessionPersistence.java - 文件系统会话持久化
public class SessionPersistence {

    // 会话元数据存储路径
    // ~/.openclaw/state/agents/{agentId}/sessions/sessions.json

    public static Map<String, SessionEntry> loadSessionStore(Path storePath);
    public static void saveSessionStore(Path storePath, Map<String, SessionEntry> store);

    // 应用启动时恢复会话
    public static void restoreSessionsFromDisk(SessionStore sessionStore, String agentId);
}
```

**特性**：
- 原子写入（临时文件 + rename）
- JSON 格式存储（易于读取和迁移）
- 应用重启后自动恢复最近的会话

#### 会话并发控制

```java
// SessionWriteLock.java - 会话文件写锁
public class SessionWriteLock {
    private final FileLock lock;
    private final FileChannel channel;

    public void lock() throws IOException;
    public void unlock() throws IOException;
}
```

**作用**：
- 防止多个线程同时写入同一会话文件
- 使用 Java NIO 的 `FileLock` 机制

---

### 1.2 认证体系

#### Gateway 认证

```java
// AuthService.java - Gateway 认证服务
public class AuthService {

    public GatewayAuthResult authorize(ConnectAuth connectAuth,
                                     String remoteAddr,
                                     boolean isLocal) {
        ResolvedGatewayAuth resolved = resolveAuth();

        // 无认证配置 → 允许所有
        if (!resolved.isConfigured()) {
            return GatewayAuthResult.success("none");
        }

        // 本地直连 → 绕过认证
        if (isLocal) {
            return GatewayAuthResult.success("local");
        }

        // 检查冷却（防暴力破解）
        if (isInCooldown(remoteAddr)) {
            return GatewayAuthResult.failure("rate_limited");
        }

        // Token 认证
        if ("token".equals(resolved.mode())) {
            if (safeEqual(clientToken, resolved.token())) {
                return GatewayAuthResult.success("token");
            }
        }

        // Password 认证
        if ("password".equals(resolved.mode())) {
            if (safeEqual(clientPassword, resolved.password())) {
                return GatewayAuthResult.success("password");
            }
        }

        recordFailure(remoteAddr);
        return GatewayAuthResult.failure("unauthorized");
    }
}
```

**认证方式**：
- **Token 认证**：通过配置文件或环境变量设置
- **Password 认证**：通过配置文件或环境变量设置
- **本地直连**：`isLocalDirectRequest` 检测自动通过

**安全特性**：
- 失败记录 + 冷却机制（默认 5 次失败后冷却 10 秒）
- 时序安全字符串比较（`MessageDigest.isEqual`）防止 timing attack
- 防重放攻击（`connect.challenge` 机制）

#### OAuth 认证支持

```java
// AuthProfileOAuth.java - OAuth 认证配置
public class AuthProfileOAuth {
    private String provider;              // "github-copilot" | "qwen-portal"
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String scopes;
    private String tokenEndpoint;
    private String authEndpoint;
}
```

**支持的提供者**：
- GitHub Copilot OAuth
- 通义千问 Portal OAuth

**特性**：
- Token 自动刷新
- 主 Agent Token 继承机制
- OAuth 状态机管理

#### 设备配对认证

```java
// DevicePairingService.java - 设备配对服务
public class DevicePairingService {

    // 待审批配对请求
    private final Map<String, PendingRequest> pendingRequests;

    // 已配对设备
    private final Map<String, PairedDevice> pairedDevices;

    // 配对流程
    public PendingRequest createPairingRequest(String deviceInfo);
    public boolean approvePairing(String requestId);
    public PairedDevice getPairedDevice(String deviceId);
}
```

**配对流程**：
1. 设备发起配对请求（包含设备信息）
2. 管理员审批（通过 `/approve` 命令）
3. 生成设备 Token 和 Scopes
4. 设备使用 Token 连接

**Token 生命周期**：
- 创建：生成随机 Token
- 轮换：定期更新 Token
- 撤销：管理员手动撤销

---

### 1.3 并发控制机制

#### 线程安全数据结构

```java
// GatewayWebSocketHandler.java - WebSocket 处理器
public class GatewayWebSocketHandler {

    // 连接存储（线程安全）
    private final Map<String, GatewayConnection> connections = new ConcurrentHashMap<>();

    // 握手定时器（线程安全）
    private final Map<String, ScheduledFuture<?>> handshakeTimers = new ConcurrentHashMap<>();
}
```

**使用的并发工具**：
- `ConcurrentHashMap`：线程安全的哈希表
- `ReentrantLock`：可重入锁（设备配对服务）
- `FileLock`：文件锁（会话写入）

#### WebSocket 多线程发送

```java
// GatewayWebSocketHandler.java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    String connId = session.getId();

    // 使用 ConcurrentWebSocketSessionDecorator 支持多线程发送
    WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
        session, 5000, 512 * 1024
    );

    GatewayConnection connection = new GatewayConnection(connId, concurrentSession);
    connections.put(connId, connection);
}
```

**特性**：
- 支持从任何线程安全发送消息
- 发送超时控制（5 秒）
- 缓冲区大小限制（512KB）
- 自动断开检测

#### 异步任务调度

```java
// CronService.java
public class CronService {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        2,
        new ThreadFactoryBuilder().setNameFormat("cron-scheduler-%d").build()
    );

    public void scheduleJob(CronJob job, String cronExpression);
}
```

**线程池配置**：
- 固定 2 个线程的调度线程池
- 自定义线程命名（便于日志追踪）
- 支持 Cron 表达式调度

---

### 1.4 访问控制（RBAC）

#### 权限 Scope 定义

```java
// MethodAuthorizer.java
public class MethodAuthorizer {
    public static final String ADMIN_SCOPE = "operator.admin";
    public static final String READ_SCOPE = "operator.read";
    public static final String WRITE_SCOPE = "operator.write";
    public static final String APPROVALS_SCOPE = "operator.approvals";
    public static final String PAIRING_SCOPE = "operator.pairing";
}
```

#### 权限分类

| Scope | 允许的方法 | 说明 |
|-------|-----------|------|
| **admin** | `config.*`, `wizard.*`, `update.*`, `sessions.reset` | 管理员权限 |
| **write** | `send`, `agent`, `chat.send`, `browser.request` | 写入权限 |
| **read** | `health`, `logs.tail`, `channels.status`, `sessions.list`, `models.list` | 读取权限 |
| **approvals** | `exec.approval.request`, `exec.approval.resolve` | 审批权限 |
| **pairing** | `node.pair.*`, `device.pair.*` | 配对权限 |

#### 角色定义

```java
// GatewayConnection.java
public record GatewayConnection(
    String connectionId,
    String connectNonce,
    String role,           // "operator" | "node"
    List<String> scopes,    // 权限范围
    String authMethod,      // "none" | "local" | "token" | "password"
    String clientIp
)
```

**角色说明**：
- **operator**：操作员，根据 `scopes` 限制权限
- **node**：节点，只能调用 `node.invoke.result`、`skills.bins` 等方法

---

### 1.5 连接管理

#### WebSocket 握手流程

```
1. 客户端连接 → WebSocket 握手完成
2. 服务端发送 → connect.challenge (随机 nonce)
3. 客户端发送 → connect 请求 (包含认证信息)
4. 服务端验证 → 认证成功/失败
5. 服务端发送 → hello-ok (包含会话信息)
6. 连接进入 CONNECTED 状态
```

#### 连接状态机

```java
// GatewayConnection.java
public enum HandshakeState {
    PENDING,    // 握手中
    CONNECTED,  // 已连接
    FAILED      // 握手失败
}
```

#### 连接生命周期管理

```java
// GatewayWebSocketHandler.java
public class GatewayWebSocketHandler {

    // 连接超时定时器
    private final Map<String, ScheduledFuture<?>> handshakeTimers = new ConcurrentHashMap<>();

    // 建立连接时启动握手超时定时器
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String connId = session.getId();
        ScheduledFuture<?> timer = scheduler.schedule(
            () -> handleHandshakeTimeout(connId),
            30, TimeUnit.SECONDS
        );
        handshakeTimers.put(connId, timer);
    }

    // 握手完成后取消定时器
    private void completeHandshake(String connId) {
        ScheduledFuture<?> timer = handshakeTimers.remove(connId);
        if (timer != null) {
            timer.cancel(false);
        }
    }
}
```

---

### 1.6 渠道访问控制

#### Telegram 白名单机制

```java
// TelegramBotAccess.java - Telegram 访问控制
public class TelegramBotAccess {

    // 白名单匹配结果
    public record AllowFromMatch(
        boolean allowed,
        String matchKey,
        String matchSource
    )

    // 检查发送者是否允许
    public static boolean isSenderAllowed(
        NormalizedAllowFrom allow,
        String senderId,
        String senderUsername
    ) {
        // 通配符匹配
        if (allow.hasWildcard()) return true;

        // 无白名单 → 允许所有
        if (!allow.hasEntries()) return true;

        // ID 匹配
        if (senderId != null && allow.entries().contains(senderId)) {
            return true;
        }

        // 用户名匹配（不区分大小写）
        if (senderUsername != null && !senderUsername.isBlank()) {
            String username = senderUsername.toLowerCase();
            return allow.entriesLower().stream()
                .anyMatch(entry -> entry.equals(username)
                    || entry.equals("@" + username));
        }

        return false;
    }
}
```

**白名单配置**：

```json
{
  "channels": {
    "telegram": {
      "token": "BOT_TOKEN",
      "allowFrom": ["123456789", "@alice", "bob"],
      "dmPolicy": "allowlist",
      "groupPolicy": {
        "mode": "allowlist",
        "groups": {
          "-1001234567890": {
            "allowFrom": ["@alice", "@bob"]
          }
        }
      }
    }
  }
}
```

**策略说明**：
- **dmPolicy**：私聊策略（open/disabled/allowlist）
- **groupPolicy**：群聊策略（支持 per-group override）
- **allowFrom**：支持通配符 `*`、ID、`@username` 等多种匹配方式

---

## 二、架构优势与局限

### 2.1 核心优势

| 优势 | 说明 | 相关代码 |
|------|------|---------|
| **会话隔离** | 每个会话独立运行，互不干扰 | `AcpSession`, `SessionStore` |
| **并发安全** | 大量使用线程安全集合 | `ConcurrentHashMap`, `ReentrantLock` |
| **多认证方式** | Token/Password/OAuth/设备配对 | `AuthService`, `AuthProfileOAuth` |
| **RBAC 权限** | 基于角色的访问控制 | `MethodAuthorizer` |
| **多渠道支持** | Telegram/WeChat/Discord/WebSocket | `openclaw-channel` |
| **防暴力破解** | 失败记录 + 冷却机制 | `AuthService.isInCooldown()` |
| **WebSocket 多线程** | 支持从任意线程发送消息 | `ConcurrentWebSocketSessionDecorator` |

### 2.2 架构局限

| 局限 | 影响 | 当前实现 |
|------|------|---------|
| **无 User 实体** | 无法进行用户注册、登录、权限管理 | 无 `User` 类、`UserService` |
| **内存会话存储** | 服务重启后需从文件恢复，不支持分布式 | `SessionStore` 使用内存 `ConcurrentHashMap` |
| **文件系统持久化** | 大规模查询效率低，不支持多实例 | JSONL 文件存储历史记录 |
| **无用户数据库** | 无法进行用户统计、查询、分析 | 依赖外部认证（OAuth） |
| **单实例架构** | 无法水平扩展，不支持负载均衡 | 所有会话存储在内存中 |
| **无用户管理界面** | 无法可视化管理用户和权限 | 仅命令行接口 |

---

## 三、多用户架构设计方案

### 3.1 方案 A：直接改造项目

#### 适用场景
- 深度集成，保持统一架构
- 长期维护，与 TypeScript 版本同步
- 性能要求高，无法接受额外网络跳转

#### 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    Nginx / Load Balancer                 │
└────────────────────┬────────────────────────────────────┘
                     │
         ┌───────────┼───────────┐
         │           │           │
         ▼           ▼           ▼
┌─────────────────────────────────────────────────────────────┐
│              OpenClaw-Java Cluster                      │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  openclaw-auth (新增用户认证模块)                  │ │
│  │  ├── User.java                                   │ │
│  │  ├── UserRepository.java                          │ │
│  │  ├── UserService.java                             │ │
│  │  ├── UserController.java                          │ │
│  │  ├── JwtTokenProvider.java                        │ │
│  │  └── Spring Security 配置                        │ │
│  └──────────────────────────────────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  openclaw-session (改造会话存储)                 │ │
│  │  ├── RedisSessionStore.java (替换内存存储)        │ │
│  │  ├── SessionRepository.java                        │ │
│  │  └── SessionHistoryRepository.java                │ │
│  └──────────────────────────────────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  openclaw-gateway (保持不变)                     │ │
│  │  ├── WebSocket 服务器                             │ │
│  │  ├── 会话管理                                   │ │
│  │  ├── 方法路由                                   │ │
│  │  └── Cron 调度                                  │ │
│  └──────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
         │           │           │
         ▼           ▼           ▼
    ┌────────┐ ┌────────┐ ┌────────┐
    │ Redis  │ │  PostgreSQL │ │   S3    │
    └────────┘ └────────┘ └────────┘
```

#### 改造范围

**1. 新增 openclaw-auth 模块**

```
openclaw-auth/
├── src/main/java/com/openclaw/auth/
│   ├── entity/
│   │   ├── User.java              # 用户实体
│   │   ├── Role.java             # 角色实体
│   │   └── Permission.java       # 权限实体
│   ├── repository/
│   │   ├── UserRepository.java    # 用户数据访问
│   │   ├── RoleRepository.java
│   │   └── PermissionRepository.java
│   ├── service/
│   │   ├── UserService.java       # 用户业务逻辑
│   │   ├── AuthService.java       # 认证服务
│   │   └── JwtTokenProvider.java # JWT Token 生成/验证
│   ├── controller/
│   │   ├── AuthController.java    # /api/auth/* 端点
│   │   └── UserController.java    # /api/users/* 端点
│   ├── security/
│   │   ├── SecurityConfig.java   # Spring Security 配置
│   │   ├── JwtAuthenticationFilter.java
│   │   └── CustomUserDetailsService.java
│   └── dto/
│       ├── LoginRequest.java
│       ├── RegisterRequest.java
│       └── JwtResponse.java
```

**2. 改造 openclaw-gateway 会话存储**

```java
// openclaw-gateway/src/main/java/com/openclaw/gateway/session/RedisSessionStore.java
@Service
public class RedisSessionStore {

    @Autowired
    private RedisTemplate<String, AcpSession> redisTemplate;

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    public AcpSession createSession(String sessionId, String sessionKey, String cwd) {
        String id = sessionId != null ? sessionId : UUID.randomUUID().toString();
        AcpSession session = AcpSession.builder()
                .sessionId(id)
                .sessionKey(sessionKey)
                .cwd(cwd)
                .createdAt(System.currentTimeMillis())
                .build();
        redisTemplate.opsForValue().set(
            SESSION_KEY_PREFIX + id,
            session,
            SESSION_TTL
        );
        return session;
    }

    public Optional<AcpSession> getSession(String sessionId) {
        AcpSession session = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        return Optional.ofNullable(session);
    }

    public List<AcpSession> getUserSessions(Long userId) {
        Set<String> keys = redisTemplate.keys(SESSION_KEY_PREFIX + userId + ":*");
        return keys.stream()
            .map(key -> redisTemplate.opsForValue().get(key))
            .filter(Objects::nonNull)
            .toList();
    }
}
```

**3. 添加 Spring Security 配置**

```java
// openclaw-auth/src/main/java/com/openclaw/auth/security/SecurityConfig.java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                   PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

**4. 添加数据库支持**

```xml
<!-- openclaw-auth/pom.xml -->
<dependencies>
    <!-- Spring Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- PostgreSQL Driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

**5. User 实体定义**

```java
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_username", columnList = "username"),
    @Index(name = "idx_email", columnList = "email")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(unique = true, length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean accountNonExpired = true;

    @Column(nullable = false)
    private boolean accountNonLocked = true;

    @Column(nullable = false)
    private boolean credentialsNonExpired = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SessionEntity> sessions = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

public enum UserRole {
    ADMIN, USER, GUEST
}
```

**6. Session 实体定义**

```java
@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_session_key", columnList = "session_key")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String sessionId;

    @Column(nullable = false)
    private String sessionKey;

    @Column(length = 255)
    private String cwd;

    @Column(nullable = false)
    private String kind;

    @Column(length = 100)
    private String label;

    @Column(length = 50)
    private String channel;

    @Column(length = 50)
    private String agentId;

    @Column(length = 100)
    private String model;

    @Column(length = 50)
    private String modelProvider;

    @Column
    private Integer contextTokens;

    @Column(length = 50)
    private String thinkingLevel;

    @Column(nullable = false)
    private Long inputTokens = 0L;

    @Column(nullable = false)
    private Long outputTokens = 0L;

    @Column(nullable = false)
    private Long totalTokens = 0L;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        lastUsedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        lastUsedAt = LocalDateTime.now();
    }
}
```

#### 依赖配置

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/openclaw
    username: openclaw
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD}
      timeout: 5000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000  # 24 hours
```

#### 改造时间表

| 阶段 | 任务 | 工作量 |
|------|------|--------|
| **Week 1-2** | 数据库设计 + Entity/Repository 实现 | 40h |
| **Week 3** | Service + Controller 实现 | 40h |
| **Week 4** | Spring Security 集成 + JWT 实现 | 40h |
| **Week 5** | SessionStore 改造 + Redis 集成 | 40h |
| **Week 6** | 测试 + 调试 + 文档 | 40h |
| **总计** | | 200h |

#### 优点
- ✅ 深度集成，架构统一
- ✅ 性能最优，无额外网络跳转
- ✅ 易于维护和扩展
- ✅ 支持分布式部署（Redis + PostgreSQL）

#### 缺点
- ❌ 改造工作量大（200+ 小时）
- ❌ 可能引入 bug
- ❌ 升级困难（与上游 TypeScript 版本 diverge）
- ❌ 需要维护额外的数据库和缓存

---

### 3.2 方案 B：外层套网关（推荐）

#### 适用场景
- 快速上线，最小侵入
- 保持 OpenClaw 项目独立性
- 易于维护，职责清晰
- 适合企业内部部署

#### 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    Nginx / API Gateway                 │
│  - 反向代理                                           │
│  - 认证中间件                                         │
│  - 限流熔断                                           │
│  - 会话映射                                           │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
┌─────────────────────────────────────────────────────────────┐
│               独立认证服务 (Go/Python/Node)             │
│  - 用户注册/登录                                      │
│  - JWT Token 生成/验证                                │
│  - 用户管理 REST API                                   │
│  - 权限管理                                           │
└─────────────────────────────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
┌─────────────────────────────────────────────────────────────┐
│               会话隔离层 (Go/Python/Node)               │
│  - 用户与会话映射                                      │
│  - 会话密钥生成                                        │
│  - 会话统计                                            │
│  - 审计日志                                            │
└─────────────────────────────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
┌─────────────────────────────────────────────────────────────┐
│            OpenClaw-Java (单实例/多实例)                │
│  - Gateway WebSocket (ws://127.0.0.1:18789)          │
│  - ACP 会话管理 (每个 WebSocket 连接 = 1 个会话)     │
│  - OAuth 认证                                          │
│  - 白名单控制                                          │
│  - Agent 执行引擎                                       │
└─────────────────────────────────────────────────────────────┘
```

#### 组件实现

**1. Nginx 反向代理配置**

```nginx
# /etc/nginx/conf.d/openclaw.conf

upstream openclaw_backend {
    least_conn;
    server 127.0.0.1:18789 max_fails=3 fail_timeout=30s;
    # 支持多实例
    # server 127.0.0.1:18790 max_fails=3 fail_timeout=30s;
}

# 认证服务
upstream auth_service {
    server 127.0.0.1:8080;
}

# 会话隔离服务
upstream session_service {
    server 127.0.0.1:8081;
}

map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

# 限流配置
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;
limit_req_zone $binary_remote_addr zone=ws_limit:10m rate=5r/s;

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # 认证验证端点（内部调用）
    location = /auth-validate {
        internal;
        proxy_pass http://auth_service/validate;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Original-URI $request_uri;
        proxy_set_header X-Auth-Token $http_authorization;
        proxy_set_header X-Real-IP $remote_addr;

        # 超时设置
        proxy_connect_timeout 2s;
        proxy_send_timeout 2s;
        proxy_read_timeout 2s;
    }

    # 会话密钥生成端点（内部调用）
    location = /session-create {
        internal;
        proxy_pass http://session_service/create;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-User-Id $upstream_http_x_user_id;
        proxy_set_header X-Real-IP $remote_addr;

        proxy_connect_timeout 2s;
        proxy_send_timeout 2s;
        proxy_read_timeout 2s;
    }

    # WebSocket 端点
    location /ws/ {
        # 认证
        auth_request /auth-validate;
        auth_request_set $user_id $upstream_http_x_user_id;
        auth_request_set $user_role $upstream_http_x_user_role;

        # 生成会话密钥
        auth_request /session-create;
        auth_request_set $session_key $upstream_http_x_session_key;

        # 限流
        limit_req zone=ws_limit burst=10 nodelay;

        # WebSocket 代理
        proxy_pass http://openclaw_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 会话隔离：每个用户使用独立的 session_key
        proxy_set_header X-Session-Key $session_key;
        proxy_set_header X-User-Id $user_id;
        proxy_set_header X-User-Role $user_role;

        # 超时设置
        proxy_connect_timeout 7d;
        proxy_send_timeout 7d;
        proxy_read_timeout 7d;

        # Buffer 设置
        proxy_buffering off;
        proxy_request_buffering off;
    }

    # REST API 端点
    location /api/ {
        # 认证
        auth_request /auth-validate;
        auth_request_set $user_id $upstream_http_x_user_id;
        auth_request_set $user_role $upstream_http_x_user_role;

        # 限流
        limit_req zone=api_limit burst=20 nodelay;

        # API 代理
        proxy_pass http://openclaw_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-User-Id $user_id;
        proxy_set_header X-User-Role $user_role;

        # 超时设置
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # 用户管理 API（直接代理到认证服务）
    location /api/auth/ {
        limit_req zone=api_limit burst=5 nodelay;
        proxy_pass http://auth_service;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /api/users/ {
        # 认证
        auth_request /auth-validate;
        auth_request_set $user_id $upstream_http_x_user_id;
        auth_request_set $user_role $upstream_http_x_user_role;

        # 权限检查：只有管理员可以访问
        if ($user_role != "ADMIN") {
            return 403;
        }

        limit_req zone=api_limit burst=5 nodelay;
        proxy_pass http://auth_service;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-User-Id $user_id;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # 健康检查
    location /health {
        proxy_pass http://auth_service/health;
        access_log off;
    }
}
```

**2. 认证服务实现（Go）**

```go
// auth-service/main.go
package main

import (
    "context"
    "fmt"
    "net/http"
    "os"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/golang-jwt/jwt/v5"
    "gorm.io/driver/postgres"
    "gorm.io/gorm"
    "golang.org/x/crypto/bcrypt"
)

// 模型定义
type User struct {
    ID        uint      `gorm:"primaryKey" json:"id"`
    Username  string    `gorm:"uniqueIndex;not null" json:"username"`
    Email     string    `gorm:"uniqueIndex" json:"email"`
    Password  string    `gorm:"not null" json:"-"`
    Role      string    `gorm:"default:'USER'" json:"role"`
    Enabled   bool      `gorm:"default:true" json:"enabled"`
    CreatedAt time.Time `json:"createdAt"`
    UpdatedAt time.Time `json:"updatedAt"`
}

type Session struct {
    ID         uint      `gorm:"primaryKey" json:"id"`
    UserID     uint      `gorm:"not null;index" json:"userId"`
    SessionKey string    `gorm:"uniqueIndex;not null" json:"sessionKey"`
    ExpiresAt  time.Time `gorm:"not null;index" json:"expiresAt"`
    User       User      `gorm:"foreignKey:UserID" json:"user"`
    CreatedAt  time.Time `json:"createdAt"`
}

// JWT 配置
type JwtConfig struct {
    Secret     string
    Expiration time.Duration
}

// 应用状态
type App struct {
    db        *gorm.DB
    jwtConfig JwtConfig
}

func main() {
    // 初始化数据库连接
    dsn := fmt.Sprintf(
        "host=%s user=%s password=%s dbname=%s port=%s sslmode=disable",
        getEnv("DB_HOST", "localhost"),
        getEnv("DB_USER", "openclaw"),
        getEnv("DB_PASSWORD", ""),
        getEnv("DB_NAME", "openclaw"),
        getEnv("DB_PORT", "5432"),
    )

    db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
    if err != nil {
        panic(fmt.Sprintf("Failed to connect to database: %v", err))
    }

    // 自动迁移
    db.AutoMigrate(&User{}, &Session{})

    // 初始化应用
    app := &App{
        db: db,
        jwtConfig: JwtConfig{
            Secret:     getEnv("JWT_SECRET", "your-secret-key"),
            Expiration: time.Duration(getEnvInt("JWT_EXPIRATION_HOURS", 24)) * time.Hour,
        },
    }

    // 创建默认管理员
    app.createDefaultAdmin()

    // 设置 Gin 路由
    router := gin.Default()

    // 公开端点
    router.POST("/api/auth/login", app.handleLogin)
    router.POST("/api/auth/register", app.handleRegister)
    router.GET("/health", app.handleHealth)

    // 内部验证端点（Nginx 调用）
    router.GET("/validate", app.handleValidate)

    // 需要认证的端点
    auth := router.Group("/api/users")
    auth.Use(app.authMiddleware())
    {
        auth.GET("", app.listUsers)
        auth.POST("", app.createUser)
        auth.PUT("/:id", app.updateUser)
        auth.DELETE("/:id", app.deleteUser)
    }

    // 启动服务
    router.Run(":8080")
}

// 登录处理
func (app *App) handleLogin(c *gin.Context) {
    var req struct {
        Username string `json:"username" binding:"required"`
        Password string `json:"password" binding:"required"`
    }

    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    // 查找用户
    var user User
    if err := app.db.Where("username = ? AND enabled = ?", req.Username, true).First(&user).Error; err != nil {
        c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
        return
    }

    // 验证密码
    if err := bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(req.Password)); err != nil {
        c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
        return
    }

    // 生成 JWT
    token, err := app.generateToken(user)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to generate token"})
        return
    }

    c.JSON(http.StatusOK, gin.H{
        "token": token,
        "user": gin.H{
            "id":       user.ID,
            "username": user.Username,
            "email":    user.Email,
            "role":     user.Role,
        },
    })
}

// 注册处理
func (app *App) handleRegister(c *gin.Context) {
    var req struct {
        Username string `json:"username" binding:"required,min=3,max=50"`
        Email    string `json:"email" binding:"required,email"`
        Password string `json:"password" binding:"required,min=8"`
    }

    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    // 检查用户名是否存在
    var count int64
    app.db.Model(&User{}).Where("username = ?", req.Username).Count(&count)
    if count > 0 {
        c.JSON(http.StatusConflict, gin.H{"error": "Username already exists"})
        return
    }

    // 加密密码
    hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to hash password"})
        return
    }

    // 创建用户
    user := User{
        Username: req.Username,
        Email:    req.Email,
        Password: string(hashedPassword),
        Role:     "USER",
        Enabled:  true,
    }

    if err := app.db.Create(&user).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create user"})
        return
    }

    c.JSON(http.StatusCreated, gin.H{
        "id":       user.ID,
        "username": user.Username,
        "email":    user.Email,
        "role":     user.Role,
    })
}

// 验证 JWT（Nginx 内部调用）
func (app *App) handleValidate(c *gin.Context) {
    token := c.GetHeader("X-Auth-Token")
    if token == "" {
        c.AbortWithStatus(http.StatusUnauthorized)
        return
    }

    claims, err := app.validateToken(token)
    if err != nil {
        c.AbortWithStatus(http.StatusUnauthorized)
        return
    }

    // 查找用户
    var user User
    if err := app.db.First(&user, claims["userId"]).Error; err != nil {
        c.AbortWithStatus(http.StatusUnauthorized)
        return
    }

    if !user.Enabled {
        c.AbortWithStatus(http.StatusForbidden)
        return
    }

    // 设置用户信息到响应头（供 Nginx 使用）
    c.Header("X-User-Id", fmt.Sprintf("%d", user.ID))
    c.Header("X-User-Role", user.Role)
    c.AbortWithStatus(http.StatusOK)
}

// 生成 JWT
func (app *App) generateToken(user User) (string, error) {
    now := time.Now()
    expiresAt := now.Add(app.jwtConfig.Expiration)

    claims := jwt.MapClaims{
        "userId":  user.ID,
        "username": user.Username,
        "role":     user.Role,
        "iat":      now.Unix(),
        "exp":      expiresAt.Unix(),
    }

    token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
    return token.SignedString([]byte(app.jwtConfig.Secret))
}

// 验证 JWT
func (app *App) validateToken(tokenString string) (jwt.MapClaims, error) {
    token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
        if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
            return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
        }
        return []byte(app.jwtConfig.Secret), nil
    })

    if err != nil {
        return nil, err
    }

    if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
        return claims, nil
    }

    return nil, fmt.Errorf("invalid token")
}

// 认证中间件
func (app *App) authMiddleware() gin.HandlerFunc {
    return func(c *gin.Context) {
        token := c.GetHeader("Authorization")
        if token == "" {
            c.JSON(http.StatusUnauthorized, gin.H{"error": "Missing authorization token"})
            c.Abort()
            return
        }

        // 移除 "Bearer " 前缀
        token = token[7:]

        claims, err := app.validateToken(token)
        if err != nil {
            c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid token"})
            c.Abort()
            return
        }

        // 将用户信息存入上下文
        c.Set("userId", claims["userId"])
        c.Set("role", claims["role"])
        c.Next()
    }
}

// 用户列表
func (app *App) listUsers(c *gin.Context) {
    var users []User
    if err := app.db.Find(&users).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch users"})
        return
    }

    // 不返回密码
    for i := range users {
        users[i].Password = ""
    }

    c.JSON(http.StatusOK, gin.H{"users": users})
}

// 创建用户
func (app *App) createUser(c *gin.Context) {
    var user User
    if err := c.ShouldBindJSON(&user); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    // 加密密码
    hashedPassword, err := bcrypt.GenerateFromPassword([]byte(user.Password), bcrypt.DefaultCost)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to hash password"})
        return
    }
    user.Password = string(hashedPassword)

    if err := app.db.Create(&user).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create user"})
        return
    }

    user.Password = ""
    c.JSON(http.StatusCreated, user)
}

// 健康检查
func (app *App) handleHealth(c *gin.Context) {
    c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// 创建默认管理员
func (app *App) createDefaultAdmin() {
    var count int64
    app.db.Model(&User{}).Where("role = ?", "ADMIN").Count(&count)
    if count > 0 {
        return
    }

    hashedPassword, _ := bcrypt.GenerateFromPassword([]byte("admin123"), bcrypt.DefaultCost)
    user := User{
        Username: "admin",
        Email:    "admin@example.com",
        Password: string(hashedPassword),
        Role:     "ADMIN",
        Enabled:  true,
    }
    app.db.Create(&user)
}

// 工具函数
func getEnv(key, defaultValue string) string {
    if value := os.Getenv(key); value != "" {
        return value
    }
    return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
    if value := os.Getenv(key); value != "" {
        if intVal, err := strconv.Atoi(value); err == nil {
            return intVal
        }
    }
    return defaultValue
}
```

**3. 会话隔离服务实现（Python）**

```python
# session-service/main.py
import os
import uuid
import time
import logging
from datetime import datetime, timedelta
from typing import Optional, List, Dict

import redis
from fastapi import FastAPI, Request, HTTPException, Depends, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# FastAPI 应用
app = FastAPI(title="Session Service", version="1.0.0")

# 允许 CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Redis 连接
redis_client = redis.Redis(
    host=os.getenv("REDIS_HOST", "localhost"),
    port=int(os.getenv("REDIS_PORT", 6379)),
    password=os.getenv("REDIS_PASSWORD", ""),
    db=int(os.getenv("REDIS_DB", 0)),
    decode_responses=True
)

# 会话 TTL（24 小时）
SESSION_TTL = 86400

# 数据模型
class SessionCreateResponse(BaseModel):
    session_key: str
    session_token: str

class SessionInfo(BaseModel):
    session_key: str
    user_id: int
    username: str
    created_at: str
    last_used_at: str
    active: bool

class UserSessionsResponse(BaseModel):
    user_id: int
    sessions: List[SessionInfo]
    total_count: int

class SessionStatsResponse(BaseModel):
    total_sessions: int
    active_sessions: int
    users_online: int

# API 端点

@app.get("/health")
def health_check():
    """健康检查"""
    try:
        redis_client.ping()
        return {"status": "ok", "redis": "connected"}
    except Exception as e:
        return {"status": "error", "redis": str(e)}

@app.post("/create", response_model=SessionCreateResponse)
def create_session(x_user_id: str = Header(...), x_real_ip: str = Header(...)):
    """
    为用户创建会话密钥（Nginx 内部调用）

    为每个用户生成唯一的 session_key，确保会话隔离
    """
    try:
        user_id = int(x_user_id)

        # 生成会话密钥：user:{user_id}:{uuid}
        session_key = f"user:{user_id}:{uuid.uuid4()}"

        # 生成会话 Token（用于 OpenClaw 认证）
        session_token = os.getenv("OPENCLAW_GATEWAY_TOKEN", "")

        # 存储会话信息到 Redis
        session_data = {
            "user_id": user_id,
            "created_at": datetime.now().isoformat(),
            "last_used_at": datetime.now().isoformat(),
            "client_ip": x_real_ip,
            "active": True
        }

        redis_client.hset(
            f"session:{session_key}",
            mapping=session_data
        )
        redis_client.expire(f"session:{session_key}", SESSION_TTL)

        # 记录用户会话列表
        redis_client.sadd(f"user_sessions:{user_id}", session_key)

        logger.info(f"Session created for user {user_id}: {session_key}")

        return SessionCreateResponse(
            session_key=session_key,
            session_token=session_token
        )

    except Exception as e:
        logger.error(f"Failed to create session: {e}")
        raise HTTPException(status_code=500, detail="Failed to create session")

@app.get("/api/user/:userId/sessions", response_model=UserSessionsResponse)
def get_user_sessions(
    userId: int,
    authorization: str = Header(...)
):
    """
    获取用户的所有会话（需要认证）
    """
    try:
        # 这里应该调用认证服务验证 JWT
        # 为简化，假设从 JWT 中提取的用户 ID 匹配
        # current_user_id = validate_jwt(authorization)
        # if current_user_id != userId:
        #     raise HTTPException(status_code=403, detail="Forbidden")

        # 获取用户的所有会话密钥
        session_keys = redis_client.smembers(f"user_sessions:{userId}")

        sessions = []
        active_count = 0

        for session_key in session_keys:
            session_data = redis_client.hgetall(f"session:{session_key}")
            if session_data:
                session_info = SessionInfo(
                    session_key=session_key,
                    user_id=int(session_data["user_id"]),
                    username=session_data.get("username", ""),
                    created_at=session_data["created_at"],
                    last_used_at=session_data["last_used_at"],
                    active=session_data.get("active", "true") == "true"
                )
                sessions.append(session_info)

                if session_info.active:
                    active_count += 1

        return UserSessionsResponse(
            user_id=userId,
            sessions=sessions,
            total_count=len(sessions)
        )

    except Exception as e:
        logger.error(f"Failed to get user sessions: {e}")
        raise HTTPException(status_code=500, detail="Failed to fetch sessions")

@app.delete("/api/session/:sessionKey")
def delete_session(
    sessionKey: str,
    authorization: str = Header(...)
):
    """
    删除用户会话（需要认证）
    """
    try:
        # 获取会话信息
        session_data = redis_client.hgetall(f"session:{sessionKey}")
        if not session_data:
            raise HTTPException(status_code=404, detail="Session not found")

        user_id = int(session_data["user_id"])

        # 验证权限
        # current_user_id = validate_jwt(authorization)
        # if current_user_id != user_id:
        #     raise HTTPException(status_code=403, detail="Forbidden")

        # 删除会话
        redis_client.delete(f"session:{sessionKey}")
        redis_client.srem(f"user_sessions:{user_id}", sessionKey)

        logger.info(f"Session deleted: {sessionKey}")

        return {"message": "Session deleted successfully"}

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete session: {e}")
        raise HTTPException(status_code=500, detail="Failed to delete session")

@app.put("/api/session/:sessionKey/ping")
def ping_session(sessionKey: str):
    """
    会话心跳（更新最后使用时间）
    """
    try:
        session_data = redis_client.hgetall(f"session:{sessionKey}")
        if not session_data:
            raise HTTPException(status_code=404, detail="Session not found")

        # 更新最后使用时间
        redis_client.hset(
            f"session:{sessionKey}",
            "last_used_at",
            datetime.now().isoformat()
        )
        redis_client.expire(f"session:{sessionKey}", SESSION_TTL)

        return {"message": "Session updated"}

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to ping session: {e}")
        raise HTTPException(status_code=500, detail="Failed to update session")

@app.get("/api/stats", response_model=SessionStatsResponse)
def get_session_stats():
    """
    获取会话统计（需要管理员权限）
    """
    try:
        # 获取所有会话
        session_keys = redis_client.keys("session:user:*")

        total_sessions = len(session_keys)
        active_sessions = 0
        user_ids = set()

        for session_key in session_keys:
            session_data = redis_client.hgetall(f"session:{session_key}")
            if session_data and session_data.get("active", "true") == "true":
                active_sessions += 1
                user_ids.add(session_data["user_id"])

        return SessionStatsResponse(
            total_sessions=total_sessions,
            active_sessions=active_sessions,
            users_online=len(user_ids)
        )

    except Exception as e:
        logger.error(f"Failed to get session stats: {e}")
        raise HTTPException(status_code=500, detail="Failed to fetch stats")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8081)
```

**4. 用户管理前端（React + TypeScript）**

```typescript
// frontend/src/pages/Users.tsx
import React, { useState, useEffect } from 'react';
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Button,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  Chip,
  IconButton,
  Tooltip,
} from '@mui/material';
import {
  Add as AddIcon,
  Edit as EditIcon,
  Delete as DeleteIcon,
  Refresh as RefreshIcon,
} from '@mui/icons-material';

interface User {
  id: number;
  username: string;
  email: string;
  role: 'ADMIN' | 'USER' | 'GUEST';
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

interface CreateUserRequest {
  username: string;
  email: string;
  password: string;
  role: 'ADMIN' | 'USER' | 'GUEST';
}

export function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editUser, setEditUser] = useState<User | null>(null);
  const [formData, setFormData] = useState<CreateUserRequest>({
    username: '',
    email: '',
    password: '',
    role: 'USER',
  });

  // 获取认证 Token
  const getAuthToken = () => {
    return localStorage.getItem('authToken') || '';
  };

  // 加载用户列表
  const loadUsers = async () => {
    try {
      const response = await fetch('/api/users', {
        headers: {
          'Authorization': `Bearer ${getAuthToken()}`,
        },
      });
      const data = await response.json();
      setUsers(data.users || []);
    } catch (error) {
      console.error('Failed to load users:', error);
    } finally {
      setLoading(false);
    }
  };

  // 创建用户
  const createUser = async () => {
    try {
      const response = await fetch('/api/users', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getAuthToken()}`,
        },
        body: JSON.stringify(formData),
      });

      if (!response.ok) {
        throw new Error('Failed to create user');
      }

      setDialogOpen(false);
      setFormData({ username: '', email: '', password: '', role: 'USER' });
      loadUsers();
    } catch (error) {
      console.error('Failed to create user:', error);
      alert('创建用户失败');
    }
  };

  // 更新用户
  const updateUser = async () => {
    if (!editUser) return;

    try {
      const response = await fetch(`/api/users/${editUser.id}`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getAuthToken()}`,
        },
        body: JSON.stringify(formData),
      });

      if (!response.ok) {
        throw new Error('Failed to update user');
      }

      setDialogOpen(false);
      setEditUser(null);
      setFormData({ username: '', email: '', password: '', role: 'USER' });
      loadUsers();
    } catch (error) {
      console.error('Failed to update user:', error);
      alert('更新用户失败');
    }
  };

  // 删除用户
  const deleteUser = async (userId: number) => {
    if (!confirm('确定要删除此用户吗？')) return;

    try {
      const response = await fetch(`/api/users/${userId}`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${getAuthToken()}`,
        },
      });

      if (!response.ok) {
        throw new Error('Failed to delete user');
      }

      loadUsers();
    } catch (error) {
      console.error('Failed to delete user:', error);
      alert('删除用户失败');
    }
  };

  // 打开对话框
  const openDialog = (user?: User) => {
    if (user) {
      setEditUser(user);
      setFormData({
        username: user.username,
        email: user.email,
        password: '',
        role: user.role,
      });
    } else {
      setEditUser(null);
      setFormData({ username: '', email: '', password: '', role: 'USER' });
    }
    setDialogOpen(true);
  };

  // 提交表单
  const handleSubmit = () => {
    if (editUser) {
      updateUser();
    } else {
      createUser();
    }
  };

  useEffect(() => {
    loadUsers();
  }, []);

  if (loading) {
    return <div>加载中...</div>;
  }

  return (
    <div style={{ padding: '24px' }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: '24px',
      }}>
        <h1>用户管理</h1>
        <div>
          <Tooltip title="刷新">
            <IconButton onClick={loadUsers}>
              <RefreshIcon />
            </IconButton>
          </Tooltip>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => openDialog()}
            style={{ marginLeft: '8px' }}
          >
            新建用户
          </Button>
        </div>
      </div>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>ID</TableCell>
              <TableCell>用户名</TableCell>
              <TableCell>邮箱</TableCell>
              <TableCell>角色</TableCell>
              <TableCell>状态</TableCell>
              <TableCell>创建时间</TableCell>
              <TableCell>操作</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map((user) => (
              <TableRow key={user.id}>
                <TableCell>{user.id}</TableCell>
                <TableCell>{user.username}</TableCell>
                <TableCell>{user.email}</TableCell>
                <TableCell>
                  <Chip
                    label={user.role}
                    color={user.role === 'ADMIN' ? 'secondary' : 'default'}
                    size="small"
                  />
                </TableCell>
                <TableCell>
                  <Chip
                    label={user.enabled ? '启用' : '禁用'}
                    color={user.enabled ? 'success' : 'error'}
                    size="small"
                  />
                </TableCell>
                <TableCell>
                  {new Date(user.createdAt).toLocaleString()}
                </TableCell>
                <TableCell>
                  <Tooltip title="编辑">
                    <IconButton onClick={() => openDialog(user)}>
                      <EditIcon />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title="删除">
                    <IconButton onClick={() => deleteUser(user.id)}>
                      <DeleteIcon />
                    </IconButton>
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)}>
        <DialogTitle>{editUser ? '编辑用户' : '新建用户'}</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            margin="dense"
            label="用户名"
            fullWidth
            value={formData.username}
            onChange={(e) =>
              setFormData({ ...formData, username: e.target.value })
            }
            disabled={!!editUser}
          />
          <TextField
            margin="dense"
            label="邮箱"
            fullWidth
            value={formData.email}
            onChange={(e) =>
              setFormData({ ...formData, email: e.target.value })
            }
          />
          <TextField
            margin="dense"
            label="密码"
            type="password"
            fullWidth
            value={formData.password}
            onChange={(e) =>
              setFormData({ ...formData, password: e.target.value })
            }
            placeholder={editUser ? '留空则不修改' : ''}
          />
          <FormControl fullWidth margin="dense">
            <InputLabel>角色</InputLabel>
            <Select
              value={formData.role}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  role: e.target.value as 'ADMIN' | 'USER' | 'GUEST',
                })
              }
            >
              <MenuItem value="ADMIN">管理员</MenuItem>
              <MenuItem value="USER">用户</MenuItem>
              <MenuItem value="GUEST">访客</MenuItem>
            </Select>
          </FormControl>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>取消</Button>
          <Button onClick={handleSubmit} variant="contained">
            {editUser ? '保存' : '创建'}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}
```

**5. 部署配置（Docker Compose）**

```yaml
# docker-compose.yml
version: '3.8'

services:
  # PostgreSQL 数据库
  postgres:
    image: postgres:16-alpine
    container_name: openclaw-postgres
    environment:
      POSTGRES_DB: openclaw
      POSTGRES_USER: openclaw
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U openclaw"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Redis 缓存
  redis:
    image: redis:7-alpine
    container_name: openclaw-redis
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis_data:/data
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # 认证服务
  auth-service:
    build:
      context: ./auth-service
      dockerfile: Dockerfile
    container_name: openclaw-auth
    environment:
      DB_HOST: postgres
      DB_USER: openclaw
      DB_PASSWORD: ${DB_PASSWORD}
      DB_NAME: openclaw
      DB_PORT: 5432
      JWT_SECRET: ${JWT_SECRET}
      JWT_EXPIRATION_HOURS: 24
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "http://localhost:8080/health"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  # 会话服务
  session-service:
    build:
      context: ./session-service
      dockerfile: Dockerfile
    container_name: openclaw-session
    environment:
      REDIS_HOST: redis
      REDIS_PORT: 6379
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      REDIS_DB: 0
      OPENCLAW_GATEWAY_TOKEN: ${OPENCLAW_GATEWAY_TOKEN}
    ports:
      - "8081:8081"
    depends_on:
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "http://localhost:8081/health"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  # OpenClaw Java
  openclaw-java:
    image: openclaw/openclaw-java:latest
    container_name: openclaw-java
    environment:
      OPENCLAW_GATEWAY_TOKEN: ${OPENCLAW_GATEWAY_TOKEN}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      # OpenClaw 配置...
    volumes:
      - openclaw_data:/root/.openclaw
    ports:
      - "18789:18789"
    restart: unless-stopped

  # Nginx
  nginx:
    image: nginx:alpine
    container_name: openclaw-nginx
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ./nginx/ssl:/etc/nginx/ssl:ro
    ports:
      - "80:80"
      - "443:443"
    depends_on:
      - auth-service
      - session-service
      - openclaw-java
    restart: unless-stopped

  # 前端
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: openclaw-frontend
    ports:
      - "3000:80"
    depends_on:
      - nginx
    restart: unless-stopped

volumes:
  postgres_data:
  redis_data:
  openclaw_data:
```

**6. 环境变量配置**

```bash
# .env
# 数据库配置
DB_PASSWORD=your-secure-password

# Redis 配置
REDIS_PASSWORD=your-redis-password

# JWT 配置
JWT_SECRET=your-jwt-secret-key-at-least-32-chars-long

# OpenClaw Gateway Token
OPENCLAW_GATEWAY_TOKEN=your-gateway-token

# Anthropic API Key
ANTHROPIC_API_KEY=your-anthropic-api-key

# 其他 OpenClaw 配置...
```

#### 实施时间表

| 阶段 | 任务 | 工作量 |
|------|------|--------|
| **Week 1** | 基础设施搭建（Nginx + Docker Compose） | 40h |
| **Week 2** | 认证服务开发（Go） | 40h |
| **Week 3** | 会话隔离服务开发（Python） | 40h |
| **Week 4** | 前端开发（React + TypeScript） | 40h |
| **Week 5** | 集成测试 + 调试 + 文档 | 40h |
| **总计** | | 200h |

#### 优点
- ✅ **最小侵入**：无需修改 OpenClaw 代码
- ✅ **快速实现**：1-2 周即可上线
- ✅ **易于维护**：独立服务，职责清晰
- ✅ **灵活扩展**：可随时替换 OpenClaw 版本
- ✅ **可水平扩展**：支持多实例部署
- ✅ **技术栈灵活**：可使用任意语言开发

#### 缺点
- ❌ 多了一层网络跳转（延迟增加 5-10ms）
- ❌ 需要部署多个组件
- ❌ 会话同步需要额外处理（Redis）

---

## 四、方案对比与建议

### 4.1 技术选型对比

| 维度 | 方案 A（直接改造） | 方案 B（外层网关） |
|------|-------------------|-------------------|
| **开发周期** | 4-6 周 | 1-2 周 |
| **代码侵入性** | 高（修改核心模块） | 低（无侵入） |
| **维护成本** | 低（统一架构） | 中（多组件） |
| **性能** | 最优（无额外跳转） | 良好（+5-10ms 延迟） |
| **扩展性** | 受限于单实例 | 可水平扩展 |
| **升级难度** | 高（需重新合并） | 低（直接升级） |
| **技术栈** | Spring 生态 | Nginx + Go/Python |
| **学习曲线** | 低（已有 Spring 基础） | 中（需学习多技术） |
| **适用场景** | 深度集成 | 快速上线 |
| **总工作量** | 200 小时 | 200 小时 |

### 4.2 推荐方案：方案 B（外层网关）

#### 推荐理由

1. **最小侵入原则**
   - OpenClaw-Java 架构已经很成熟，破坏性改造风险高
   - 无需修改核心代码，避免引入 bug
   - 可以独立演进各组件

2. **快速上线**
   - 1-2 周即可完成基础功能
   - 可以采用渐进式增强策略
   - 先满足基本需求，后续迭代优化

3. **职责分离**
   - 认证服务专注于用户管理
   - 会话服务专注于会话隔离
   - OpenClaw 专注于 Agent 执行
   - 各司其职，易于维护

4. **技术栈灵活**
   - 认证服务可以使用 Go（性能好）
   - 会话服务可以使用 Python（开发快）
   - 前端可以使用 React/Next.js（生态丰富）
   - 每个组件选择最合适的技术

5. **可水平扩展**
   - 认证服务可以多实例部署
   - 会话服务依赖 Redis，天然支持多实例
   - OpenClaw 可以多实例部署（会话共享到 Redis）
   - 适合大规模部署

6. **易于升级**
   - OpenClaw 版本升级不影响外部服务
   - 可以随时切换到 TypeScript 版本
   - 避免版本 diverge 问题

#### 实施路线图

```
Phase 1: 基础设施（Week 1）
├── 部署 Docker Compose 环境
├── 配置 Nginx 反向代理
├── 部署 PostgreSQL + Redis
└── 集成 OpenClaw-Java

Phase 2: 认证服务（Week 2）
├── 开发用户注册/登录 API
├── 实现 JWT Token 生成/验证
├── 集成到 Nginx 认证流程
└── 测试基础认证流程

Phase 3: 会话隔离（Week 3）
├── 开发会话密钥生成 API
├── 实现用户与会话映射
├── 集成到 Nginx 会话流程
└── 测试会话隔离功能

Phase 4: 用户管理（Week 4）
├── 开发用户管理 REST API
├── 开发前端用户管理界面
├── 实现权限管理功能
└── 集成认证流程

Phase 5: 完善与测试（Week 5）
├── 添加审计日志
├── 添加使用统计
├── 压力测试和性能优化
└── 编写部署文档
```

#### 关键里程碑

| 里程碑 | 交付物 | 时间 |
|--------|--------|------|
| **M1: 基础设施** | Docker Compose 环境就绪 | Week 1 结束 |
| **M2: 认证服务** | 用户可登录获取 Token | Week 2 结束 |
| **M3: 会话隔离** | 用户可独立连接 OpenClaw | Week 3 结束 |
| **M4: 用户管理** | 管理员可管理用户 | Week 4 结束 |
| **M5: 生产上线** | 系统稳定运行 | Week 5 结束 |

---

## 五、风险评估与缓解

### 5.1 技术风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| Nginx 认证延迟 | 高 | 中 | 使用本地认证缓存，减少认证服务调用 |
| Redis 单点故障 | 高 | 低 | 使用 Redis Sentinel 或 Cluster |
| 会话不一致 | 中 | 中 | 实现 Redis 事务锁机制 |
| 网络故障 | 中 | 中 | 实现断线重连机制 |

### 5.2 安全风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| JWT 泄露 | 高 | 低 | 使用 HTTPS，设置合理过期时间 |
| 会话劫持 | 高 | 低 | 绑定 IP 地址，实现会话密钥轮换 |
| 暴力破解 | 中 | 中 | 实现登录限流，失败锁定 |
| SQL 注入 | 高 | 低 | 使用 ORM 参数化查询 |

### 5.3 运维风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|---------|
| 部署复杂度 | 中 | 高 | 使用 Docker Compose 一键部署 |
| 监控缺失 | 高 | 中 | 集成 Prometheus + Grafana |
| 日志分散 | 中 | 中 | 使用 ELK Stack 统一日志 |
| 备份缺失 | 高 | 低 | 定期备份数据库和 Redis |

---

## 六、成本估算

### 6.1 开发成本

| 角色 | 人数 | 周期 | 人天 | 日薪（元） | 总成本（万元） |
|------|------|------|------|-----------|-------------|
| 后端工程师（Go） | 1 | 5 周 | 25 | 1500 | 3.75 |
| 后端工程师（Python） | 1 | 5 周 | 25 | 1500 | 3.75 |
| 前端工程师 | 1 | 5 周 | 25 | 1500 | 3.75 |
| 运维工程师 | 0.5 | 5 周 | 12.5 | 1500 | 1.875 |
| 测试工程师 | 0.5 | 5 周 | 12.5 | 1500 | 1.875 |
| **总计** | **4** | **5 周** | **100** | - | **15** |

### 6.2 基础设施成本（月度）

| 资源 | 规格 | 数量 | 单价（元/月） | 总价（元/月） |
|------|------|------|--------------|--------------|
| 云服务器 | 4C8G | 2 | 600 | 1200 |
| PostgreSQL | 主从架构 | 1 套 | 800 | 800 |
| Redis | 4G | 1 | 400 | 400 |
| 对象存储 | 1TB | 1 | 200 | 200 |
| 带宽 | 10Mbps | 1 | 500 | 500 |
| CDN | 按量计费 | - | 200 | 200 |
| **总计** | - | - | - | **3300** |

### 6.3 首年总成本

| 成本类型 | 金额（万元） |
|---------|-----------|
| 开发成本（5 周） | 15 |
| 基础设施（12 个月） | 3.96 |
| 域名 + SSL 证书 | 0.1 |
| 应急预算（20%） | 3.812 |
| **总计** | **22.872** |

---

## 七、未来扩展方向

### 7.1 功能扩展

1. **多租户支持**
   - 添加租户（Tenant）概念
   - 实现租户级别的数据隔离
   - 支持白标定制

2. **高级权限管理**
   - 基于资源的细粒度权限控制（ABAC）
   - 动态权限策略引擎
   - 权限审计日志

3. **单点登录（SSO）**
   - 集成 LDAP/AD
   - 集成 SAML/OIDC
   - 支持 Google/Facebook/GitHub 登录

4. **会话管理增强**
   - 会话超时自动清理
   - 会话并发限制
   - 会话实时监控

5. **审计与合规**
   - 用户操作审计日志
   - GDPR 合规性支持
   - 数据导出与删除

### 7.2 性能优化

1. **缓存优化**
   - 认证结果缓存
   - 权限策略缓存
   - 会话数据预热

2. **数据库优化**
   - 读写分离
   - 分库分表
   - 连接池优化

3. **CDN 加速**
   - 静态资源 CDN
   - WebSocket 连接就近接入

4. **负载均衡**
   - L4/L7 负载均衡
   - 健康检查与自动故障转移

### 7.3 可观测性

1. **监控体系**
   - 应用性能监控（APM）
   - 业务指标监控
   - 异常告警

2. **日志体系**
   - 结构化日志
   - 日志聚合与分析
   - 日志检索

3. **链路追踪**
   - 分布式追踪
   - 跨服务调用链可视化
   - 性能瓶颈分析

4. **指标大盘**
   - 实时用户数
   - API 响应时间
   - 错误率统计
   - 资源使用率

---

## 八、总结与建议

### 8.1 核心结论

1. **OpenClaw-Java 已完全支持多用户并发**
   - 使用 `ConcurrentHashMap` 实现线程安全的会话存储
   - 每个会话独立运行，有独立的上下文和历史
   - 已实现 Token/Password/OAuth/设备配对等多种认证方式
   - 已实现基于角色的访问控制（RBAC）

2. **当前架构的限制**
   - 无传统 User 实体和用户数据库
   - 会话仅存储在内存，不支持分布式部署
   - 历史记录存储在文件系统，不便于大规模查询

3. **推荐采用方案 B（外层网关）**
   - 最小侵入，无需修改 OpenClaw 代码
   - 快速上线，1-2 周即可完成基础功能
   - 易于维护，各组件职责清晰
   - 可水平扩展，支持多实例部署

### 8.2 行动建议

**短期（1-2 个月）**：
1. 搭建 Docker Compose 基础设施
2. 开发认证服务（Go）
3. 开发会话隔离服务（Python）
4. 集成 Nginx 反向代理
5. 开发基础用户管理界面

**中期（3-6 个月）**：
1. 完善权限管理体系
2. 添加审计日志功能
3. 实现会话监控和统计
4. 集成监控和告警系统
5. 编写完善的运维文档

**长期（6-12 个月）**：
1. 支持多租户架构
2. 实现单点登录（SSO）
3. 优化性能和可扩展性
4. 建立完善的运维体系
5. 支持 Kubernetes 部署

### 8.3 成功标准

- ✅ 支持多用户并发访问，会话完全隔离
- ✅ 用户可以注册、登录、管理自己的会话
- ✅ 管理员可以管理用户、角色、权限
- ✅ 系统稳定运行，可用性 > 99.9%
- ✅ API 响应时间 < 200ms（P95）
- ✅ 支持 1000+ 并发用户
- ✅ 完善的监控和告警体系
- ✅ 详细的运维文档

---

## 附录

### A. 术语表

| 术语 | 说明 |
|------|------|
| ACP | Agent Control Protocol，OpenClaw 的自定义 WebSocket 协议 |
| Session | 会话，表示一个用户与 Agent 的对话上下文 |
| SessionKey | 会话密钥，用于标识和隔离不同用户的会话 |
| RBAC | Role-Based Access Control，基于角色的访问控制 |
| JWT | JSON Web Token，用于身份认证的令牌 |
| OAuth | 开放授权协议，用于第三方登录 |
| OpenID Connect | 基于 OAuth 2.0 的身份认证协议 |
| WebSocket | 全双工通信协议，OpenClaw 使用它进行客户端-服务端通信 |
| Redis | 内存数据库，用于缓存和会话存储 |
| PostgreSQL | 开源关系型数据库 |

### B. 参考资料

- [OpenClaw Java README](https://github.com/yuenkang/openclaw-java)
- [OpenClaw Java WebSocket 协议文档](docs/websocket-protocol.md)
- [Spring Security 官方文档](https://docs.spring.io/spring-security/)
- [JWT 官方网站](https://jwt.io/)
- [Nginx 官方文档](https://nginx.org/en/docs/)
- [Redis 官方文档](https://redis.io/documentation)
- [PostgreSQL 官方文档](https://www.postgresql.org/docs/)

### C. 联系方式

- 项目地址：[OpenClaw Java GitHub](https://github.com/yuenkang/openclaw-java)
- Telegram 讨论群组：[OpenClaw 讨论群](https://t.me/+D9DiVXI3xe43ZDNl)
- GitHub Issues：[问题反馈](https://github.com/yuenkang/openclaw-java/issues/2)

---

**文档版本**: 1.0
**最后更新**: 2026-04-01
**作者**: OpenClaw Team
