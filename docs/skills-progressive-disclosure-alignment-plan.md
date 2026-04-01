# Skills 渐进式披露对齐方案（收敛版）

本文档将 `openclaw-java/docs/skills-progressive-disclosure-alignment-review.md` 与 `openclaw-java/docs/skills-progressive-disclosure-alignment-review-detailed.md` 中的评审意见收口为可实施规格。

目标不是只把 prompt 从“正文直注入”改成“catalog 注入”，而是把 Java 侧 skills 的整条链路收敛到与 TS 原生实现一致的行为基线：

- 多源发现
- 安全加载
- eligibility gating
- catalog prompt
- progressive disclosure
- 可观测性
- 迁移兼容

适用范围：
- `openclaw-agent` 的 skill 发现、过滤、提示词注入、运行时 trace
- `openclaw-app` / `openclaw-gateway` 发起的主对话请求
- `skills.status`、日志、集成测试与验收

不在本文档范围：
- ClawHub 安装协议
- `/skill:name` 的完整产品交互设计
- `_meta.json` 作为运行时强依赖
- 最终面向 skill 作者的外部文档表述

---

## 1. 最终决策摘要

本次收敛后，Java 方案按以下决策执行：

1. **对齐对象不是单一 prompt 片段，而是完整 skill pipeline。** 第一版必须同时覆盖发现、过滤、安全、prompt、trace，不能只改 prompt 形态。
2. **首轮系统提示只注入 catalog，不注入 `SKILL.md` 正文。** 正文只在模型命中 skill 后通过 `read_file` 按需读取。
3. **Prompt-facing catalog 只包含 `name`、`description`、`location`。** `location` 指向 canonical 绝对路径的 `SKILL.md`。
4. **Java 在路径策略上有意偏离 TS 的 `~/...` prompt 压缩。** Java 默认输出绝对路径，`~/$HOME/${HOME}` 仅作为运行时兼容输入。
5. **Java 在工具名文案上有意偏离 TS。** Prompt 中使用 `read_file`，不照抄 TS 的 `read`。
6. **Skill 发现范围第一版就对齐到多源模型。** 至少包括：`extraDirs`、bundled、managed、`~/.agents/skills`、`<workspace>/.agents/skills`、`<workspace>/skills`；`<workspace>/.openclaw/skills` 作为 Java 兼容源保留一轮迁移期。
7. **发现与 prompt 前过滤必须先完成 eligibility gating。** 包括 `enabled`、bundled allowlist、`os`、`requires.*`、remote eligibility、`always`、`disable-model-invocation`。
8. **Loader 安全合同第一版必做。** 包括扫描数量上限、单 skill 文件大小上限、realpath 越界拒绝、symlinked `SKILL.md` 拒绝、异常路径跳过。
9. **Prompt 预算规则按 TS 现状收口为字符预算，不是 token 预算。** 默认值：`maxCandidatesPerRoot=300`、`maxSkillsLoadedPerSource=200`、`maxSkillsInPrompt=150`、`maxSkillsPromptChars=30000`、`maxSkillFileBytes=256000`。
10. **Prompt 降级顺序固定为三档。** `full(name+description+location)` -> `compact(name+location)` -> `compact + stable prefix truncation`。
11. **Skill 内容不做额外缓存或 pin。** 读取结果只依赖消息历史自然保留；若后续被 compaction 挤出，模型可再次 `read_file`。
12. **Java 侧选择“有限 tool alias 注册”解决 TS skill 正文工具名不一致。** 第一批别名覆盖 `read`、`write`、`grep`、`ls`、`bash` 到 Java 现有工具；不做正文字符串替换。
13. **命名迁移采用双轨过渡。** Prompt-facing `name = frontmatter.name ?? 目录名`；config lookup `skillKey ?? name`，并保留对旧目录名 key 的过渡 fallback。
14. **`skills.status` 定位为静态能力与 eligibility 报告。** 动态 promptMode、注入结果、skill read trace 走 run-scoped 日志与调试数据，不与静态报告混用。
15. **“模型首轮一定先读 skill”不作为 deterministic 发布门槛。** 发布门槛以确定性单测/集成测试为主，这类行为作为统计评测项。
16. **现有 `SkillConfigResolver` 是 eligibility 的基础能力，不重复发明第二套规则。** 新 prompt pipeline 直接复用它，并补齐尚未接通到 prompt 构建链路的分支。
17. **当前 Java 配置模型尚无 `skills.limits`。** 方案实施需要新增 `OpenClawConfig.SkillsLimitsConfig`，而不是只在文档里写“可配置”。
18. **当前 loader 与 watcher 的路径口径不一致。** `SkillLoader` 还在读 `<workspace>/.openclaw/skills`，`SkillRefresh` 已经在 watch `<workspace>/skills`；该不一致需要作为显式迁移项处理。
19. **当前 `AgentRunContext` 没有 run-scoped skill state。** 若要实现 `resolvedSkills`、promptMode、skill read trace，需新增上下文字段或单独的 `SkillsRunState`。

---

## 2. 对齐基线

### 2.1 TS 基线中必须照搬的合同

以下条目属于 TS 现有行为合同，Java 不再把它们视为“可选增强”：

| 维度 | TS 基线 | Java 决策 |
|---|---|---|
| 发现来源 | extra、bundled、managed、personal `.agents/skills`、project `.agents/skills`、workspace `skills` | 第一版全部纳入 |
| 覆盖优先级 | `extra < bundled < managed < personal .agents/skills < project .agents/skills < workspace` | 按同顺序实现 |
| prompt 前过滤 | `enabled`、bundled allowlist、`os`、`requires.*`、remote eligibility、`always` | 第一版全部纳入 |
| invocation policy | `disableModelInvocation=true` 不进入 prompt；`userInvocable=false` 只影响命令注册 | 按同语义实现 |
| frontmatter | YAML frontmatter + 结构化值 JSON 字符串化 + inline 冒号优先保护 | 按同语义实现 |
| metadata | 只读取 `metadata` 字段，用 JSON5 解析，再取 `openclaw + LEGACY_MANIFEST_KEYS` | 按同语义实现 |
| install | 仅允许 `brew|node|go|uv|download`，非法项丢弃不报错中断 | 按同语义实现 |
| prompt tiers | full -> compact -> compact+truncation | 按同顺序实现 |
| XML escaping | `name`/`description`/`location` 必须转义 | 必做 |
| skill lifecycle | 不额外缓存正文，依赖消息历史 | 按同语义实现 |

### 2.2 TS 默认阈值

Java 第一版直接采用 TS 当前默认值，并允许通过配置覆盖：

| 配置项 | 默认值 | 含义 |
|---|---:|---|
| `maxCandidatesPerRoot` | `300` | 单个 root 在“是否像 skills root”判断与候选扫描时的保护上限 |
| `maxSkillsLoadedPerSource` | `200` | 单个 source 最多加载的 skill 数 |
| `maxSkillsInPrompt` | `150` | prompt 构建前按数量截断的上限 |
| `maxSkillsPromptChars` | `30000` | skills prompt 的字符预算 |
| `maxSkillFileBytes` | `256000` | 单个 `SKILL.md` 最大字节数 |
| `compactWarningOverhead` | `150` | compact 模式额外保留给 warning 文案的预算 |

补充说明：
- Java 第一版按字符数做 budget，而不是自行引入 token estimator。
- 因为 Java prompt 中默认使用绝对路径，不压缩成 `~/...`，所以在 home 目录较深的环境里会比 TS 更早触发 compact/truncated。这是接受的显式取舍。
- 当前 `OpenClawConfig.SkillsConfig` 只有 `entries`、`allowBundled`、`load`，没有 `limits`。因此本方案现在明确要求补充配置模型：
  - `skills.limits.maxCandidatesPerRoot`
  - `skills.limits.maxSkillsLoadedPerSource`
  - `skills.limits.maxSkillsInPrompt`
  - `skills.limits.maxSkillsPromptChars`
  - `skills.limits.maxSkillFileBytes`
  - 若实现节奏需要拆分，Phase A/B 之前可先以常量落地，但必须在文档中标明“暂未开放配置覆盖”，不能假装已有。

### 2.3 Java 的显式偏离项

以下偏离属于有意设计，不视为“未对齐”：

1. **读取工具名偏离**：TS prompt 写 `read`，Java prompt 写 `read_file`。
2. **路径表达偏离**：TS prompt 默认 compact home path，Java prompt 默认 canonical 绝对路径。
3. **warning 跳转偏离**：TS warning 指向 CLI 检查，Java warning 指向 `skills.status` 或内部调试面。

这些偏离必须在代码和文档中显式标注为 Java runtime 约束，而不是实现人员自由发挥。

---

## 3. Java 当前需要纠正的偏差

当前 Java 与本方案目标状态的主要差距如下：

1. **注入形态错误**：仍在把 skill 正文直接塞进系统提示。
2. **发现范围不完整**：仍偏向 workspace 单源，未覆盖 TS 多源模型。
3. **gating 过弱**：尚未把 `enabled`、allowlist、`requires.*`、remote eligibility、`disableModelInvocation` 全量纳入 prompt 前过滤。
   - 需要补充说明：`SkillConfigResolver` 已经实现了大部分 eligibility 规则，但当前 prompt 构建链路没有复用它，问题在“未接线”，不在“完全没有逻辑”。
4. **Loader 安全基线不足**：缺少 realpath 越界保护、symlinked `SKILL.md` 拒绝、数量/字节上限。
5. **metadata 兼容链路不足**：frontmatter 解析、JSON5、manifest key、install 校验、默认值合同仍不完整。
6. **命名语义仍偏目录名**：尚未把 `name` / `skillKey` / legacy directory key 的迁移路径写清。
7. **运行时观测不足**：还不能稳定回答“本轮注入了什么”“是否 compact”“模型是否真的读了 skill 文件”。
8. **技能正文中的工具名存在深层不兼容**：TS skill 常见指令名与 Java registry 不一致。
9. **刷新链路与加载链路口径不一致**：`SkillRefresh` 已经 watch `<workspace>/skills` 和 `extraDirs`，但 `SkillLoader` 仍默认读取 `<workspace>/.openclaw/skills`。
10. **配置与上下文结构仍有缺口**：
   - `OpenClawConfig` 尚无 `skills.limits`
   - `AgentRunContext` 尚无 run-scoped skill state
   - `SkillSource` 枚举还不足以表达 `personal/project agents` 与 `legacy workspace`

---

## 4. 目标行为与实现合同

### 4.1 发现来源与覆盖优先级

Java 第一版的 source 范围定义如下：

| source group | 目录 | 说明 |
|---|---|---|
| extra | `config.skills.load.extraDirs` + plugin skill dirs | 非核心目录，最低优先级 |
| bundled | runtime bundled skills dir | 与 TS 保持一致 |
| managed | managed skills dir | 与 TS 保持一致 |
| personal agents | `~/.agents/skills` | 与 TS 保持一致 |
| project agents | `<workspace>/.agents/skills` | 与 TS 保持一致 |
| legacy workspace | `<workspace>/.openclaw/skills` | Java 迁移兼容源，非 TS 基线 |
| workspace | `<workspace>/skills` | 最高优先级 |

覆盖优先级按表中顺序从低到高覆盖。

决策细节：
- 同名 skill 发生冲突时，**高优先级 source 覆盖低优先级 source 的内容**。
- prompt/truncation 顺序采用**稳定顺序**，不引入最近使用频率或 description 长度排序。
- 同一 source 内按目录名字典序稳定排序。
- Java 兼容源 `<workspace>/.openclaw/skills` 保留一个迁移周期；当 `<workspace>/skills` 中存在同名 skill 时，由后者覆盖。
- 由于当前 `SkillTypes.SkillSource` 只包含 `BUNDLED/MANAGED/WORKSPACE/EXTRA/PLUGIN`，实现时需要二选一：
  - 扩展枚举，增加 `AGENTS_PERSONAL`、`AGENTS_PROJECT`、`WORKSPACE_LEGACY`
  - 或保留内部枚举不变，但在 status/snapshot 层单独输出更细粒度的 source label
  - 本方案倾向第一种，避免 status、trace、precedence 逻辑继续依赖隐式约定

### 4.2 Loader 安全与扫描规则

`SkillLoader` 必须从“朴素目录扫描器”升级为“带安全合同的 catalog loader”。

强制规则：

1. 所有待加载路径都必须先 `realpath`，并验证仍位于各自 root 之下。
2. `SKILL.md` 为 symlink 时直接跳过，即使 symlink 目标仍在 root 内。
3. 单个 `SKILL.md` 大小超过 `256000` 字节时跳过并告警。
4. 单个 source 的候选扫描数、加载数必须受上限保护。
5. 只接受 UTF-8 文本；读文件异常、编码异常、权限异常统一按“跳过该 skill + 记录 warn”处理。
6. root 自身可以是 skill 目录；若 root 下存在 `skills/*/SKILL.md` 的嵌套结构，允许识别 nested root。
7. plugin skill dirs 合并到 extra source 组，不单独创造新的 prompt 优先级层级。

### 4.3 Frontmatter、metadata 与 invocation policy

Java 必须把解析链路收口到以下合同：

#### 4.3.1 frontmatter 解析

- 仅识别文首 `--- ... ---` frontmatter block。
- 先按 YAML 解析；对象/数组等结构化值统一序列化成 JSON 字符串。
- 若某字段 inline 值中包含冒号，且 YAML 解析把它错误识别成 structured value，则保留 inline 字符串值。
- YAML 解析失败时，回退到行级 `key: value` 提取；若仍无法取得 `description`，该 skill 不进入 catalog。

#### 4.3.2 metadata 解析

- 仅从 `frontmatter["metadata"]` 读取元数据。
- `metadata` 用 JSON5 解析。
- manifest block 只认：`openclaw + LEGACY_MANIFEST_KEYS`。
- Java 现有额外 key（如 `pi`、`manifest`）不再默认进入核心解析链路；若保留，只能通过显式兼容开关启用。

#### 4.3.3 字段语义

以下字段必须与 TS 行为同构：

- 标量字段：`always`、`emoji`、`homepage`、`skillKey`、`primaryEnv`
- 列表字段：`os`
- requires：`bins`、`anyBins`、`env`、`config`
- install：`brew|node|go|uv|download`

install 的校验要求：
- 允许 kind/type 双写法，但最终规范化为 `kind`
- 只保留白名单字段
- 非法 formula / package / module / URL 直接丢弃该 install item
- install item 非法时**不应导致整个 skill 不可见**

#### 4.3.4 invocation policy

默认值固定为：

- `user-invocable = true`
- `disable-model-invocation = false`

行为合同：
- `disable-model-invocation = true`：skill 不进入 prompt-facing catalog，但仍出现在 `skills.status` 静态报告中。
- `user-invocable = false`：只影响 slash command / direct dispatch 生成，不影响 prompt 自动发现。

### 4.4 名称语义、skillKey 与迁移兼容

这是 Java 必须单独做出的迁移决策。

#### 4.4.1 Prompt-facing name

- catalog 中的 `name` 使用 `frontmatter.name ?? 目录名`
- `description` 为空时，该 skill 不进入 catalog

#### 4.4.2 Config lookup key

- 主 key：`metadata.skillKey ?? name`
- 迁移期 fallback：若未命中，再尝试 legacy directory name

#### 4.4.3 过渡期策略

- `skills.status` 中同时返回 `name`、`skillKey`，以及在 `name != 目录名` 时返回 `legacyDirName`
- 日志默认展示 `name`，必要时追加 `(legacyDirName=...)`
- 旧配置 `skills.entries.<目录名>` 在一个迁移周期内继续生效
- 迁移周期结束后移除 legacy directory key fallback

### 4.5 Prompt 构建合同

#### 4.5.1 full format

默认 prompt 形态如下：

```xml
The following skills provide specialized instructions for specific tasks.
Use the read_file tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>word-docx</name>
    <description>Create, inspect, and edit Microsoft Word documents...</description>
    <location>/abs/path/to/SKILL.md</location>
  </skill>
</available_skills>
```

约束：
- `name` / `description` / `location` 必须做 XML escaping
- `location` 必须可直接作为 `read_file(path=...)` 的输入
- catalog 中不包含正文，不包含 metadata JSON，不包含 install 明细

#### 4.5.2 compact format

当 full format 超过预算时，降级为：

```xml
The following skills provide specialized instructions for specific tasks.
Use the read_file tool to load a skill's file when the task matches its name.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>word-docx</name>
    <location>/abs/path/to/SKILL.md</location>
  </skill>
</available_skills>
```

compact 规则：
- 只省略 `description`
- 不改 `location` 的可达语义
- 优先保留所有 skill 的可发现性

#### 4.5.3 truncated format

预算流程固定为：

1. 先按 `maxSkillsInPrompt = 150` 做数量截断
2. 若 full format 可放下，则使用 full
3. 否则尝试 compact
4. 若 compact 可放下，则保留全部已选 skill，省略 description
5. 若 compact 仍放不下，则对当前稳定顺序做 prefix binary search，保留能放下的最大前缀

附加规则：
- compact 模式需要预留 `150` 字符给 warning 文案
- warning 文案必须稳定，且不依赖 LLM 行为
- Java warning 文案引用 `skills.status`，不引用 TS CLI

建议 warning 文案：

- compact 且未丢 skill：
  - `⚠️ Skills catalog using compact format (descriptions omitted). Check skills.status for full inventory.`
- truncated：
  - `⚠️ Skills truncated: included {included} of {total}{compactSuffix}. Check skills.status for full inventory.`

### 4.6 Prompt 前 eligibility gating

进入 prompt catalog 之前，必须先完成以下过滤：

1. `skills.entries.<key>.enabled != false`
2. bundled allowlist 未阻断
3. `os` 满足当前平台
4. `requires.bins` / `requires.anyBins` 满足
5. `requires.env` / `requires.config` 满足
6. remote eligibility 满足（如有 remote context）
7. `disable-model-invocation != true`

补充说明：
- `always` 不是“绕过 requirements”的语义，而是 eligibility 评估输入的一部分，应与 TS 保持一致。
- `eligible=false` 的 skill 不进入 prompt，但仍应进入 `skills.status`。
- 当前实现上，不建议在 `SkillLoader` 内再造一套 `enabled/allowBundled/requires/always/remote` 判断。以 `SkillConfigResolver.shouldIncludeSkill(...)` 为单一事实源，再把缺失的 `disableModelInvocation` prompt 过滤接到后续阶段。

### 4.7 运行时读取、路径归一化与生命周期

#### 4.7.1 路径归一化

统一入口同时服务于 catalog 与运行时 `read_file` 入参比对。

输入支持：
- 绝对路径
- `~/...`
- `$HOME/...`
- `${HOME}/...`
- 相对路径

归一化顺序：
1. 展开 home 前缀
2. skill 场景下先相对 `baseDir` 解析；普通文件场景按 `cwd` 解析
3. `normalize + toAbsolutePath + realpath`（若文件存在）
4. 与注入时保存的 canonical `location` 比对

输出策略：
- prompt 中始终使用 canonical 绝对路径
- 日志可同时记录 raw path 与 normalized path
- UI 若需压缩显示，可单独渲染 display path，但不可替代运行时主键

#### 4.7.2 生命周期与缓存

Java 与 TS 一致，不增加 skill 正文专用缓存：

- skill 正文通过 `read_file` 的 tool result 进入消息历史
- 同一对话后续轮次是否还能“记住”正文，取决于消息历史是否仍在上下文内
- 若被后续长对话或 compaction 挤出，模型再次使用 skill 时允许重新 `read_file`
- runtime 只保存“本轮注入过哪些 canonical location”用于 trace，不保存 skill body cache

### 4.8 异常处理与降级规则

| 场景 | 决策 |
|---|---|
| source 目录不存在 | 视为无 skill，debug 记录即可，不报错中断 |
| `SKILL.md` 不存在 | 跳过该目录 |
| `SKILL.md` 为空 / 无 description | 跳过该 skill |
| frontmatter 解析失败，无法拿到 `description` | 跳过该 skill，并记 warn |
| metadata 解析失败 | 保留 skill 基本可见性，metadata 置空，invocation 用默认值 |
| install item 非法 | 丢弃非法 item，不中断 skill |
| `SKILL.md` 非 UTF-8 / 读文件异常 | 跳过该 skill，并记 warn |
| 文件越界 root / symlinked `SKILL.md` | 跳过该 skill，并记 warn |
| 同名 skill 出现在多个 source | 按覆盖优先级取高优先级版本 |
| prompt 构建后 0 个 skill 能放入预算 | 返回 warning + 空 skills block 或空 prompt；不回退正文注入 |
| 运行时 `read_file(location)` 失败 | 按普通 tool error 返回给模型；若命中 injected skill location，额外记 `skill read failed` trace |

### 4.9 TS skill 正文工具名兼容

该问题不能靠 prompt 文案解决，必须做 Java 侧架构决策。

最终决策：**采用有限 tool alias 注册，不做正文 rewrite。**

第一批 alias：

| TS 常见名 | Java 实际工具 |
|---|---|
| `read` | `read_file` |
| `write` | `write_file` |
| `grep` | `grep_search` |
| `ls` | `list_dir` |
| `bash` | `exec` |

实施约束：
- alias 与 canonical tool 共用同一权限、审计、执行路径
- alias 不单独实现业务逻辑
- alias 范围保持有限，只覆盖确有复用价值且语义完全一致的工具
- 不通过正则替换 skill 正文内容来“伪兼容”

### 4.10 可观测性与状态面

#### 4.10.1 `skills.status` 的定位

`skills.status` 定位为**静态能力与 eligibility 报告**，返回字段至少包括：

```json
{
  "ts": 1234567890,
  "workspaceDir": "/abs/workspace",
  "managedSkillsDir": "/abs/managed",
  "limits": {
    "maxCandidatesPerRoot": 300,
    "maxSkillsLoadedPerSource": 200,
    "maxSkillsInPrompt": 150,
    "maxSkillsPromptChars": 30000,
    "maxSkillFileBytes": 256000
  },
  "skills": [
    {
      "name": "word-docx",
      "legacyDirName": "word-docx",
      "skillKey": "word-docx",
      "description": "Create, inspect, and edit Microsoft Word documents...",
      "source": "openclaw-managed",
      "bundled": false,
      "filePath": "/abs/path/to/SKILL.md",
      "baseDir": "/abs/path/to/skill-dir",
      "always": false,
      "disabled": false,
      "blockedByAllowlist": false,
      "eligible": true,
      "userInvocable": true,
      "disableModelInvocation": false,
      "requirements": {},
      "missing": {},
      "configChecks": [],
      "install": []
    }
  ]
}
```

#### 4.10.2 动态注入结果

动态信息不并入 `skills.status` 主合同，而是放在 run-scoped 日志/调试数据中：

- `runId`
- `workspaceDir`
- `discoveredCount`
- `eligibleCount`
- `promptEligibleCount`
- `injectedCount`
- `promptMode = full|compact|truncated|empty`
- `injectedSkillNames`
- `injectedSkillLocations`
- `metadataMode = ts-parity|legacy-compat`
- `locationMode = canonical-absolute`

建议日志：

```text
skills prompt built: runId=... workspace=/path discovered=18 eligible=12 promptEligible=11 injected=11 promptMode=compact metadataMode=ts-parity locationMode=canonical-absolute names=[word-docx,pdf-tools,...]
```

#### 4.10.3 skill read trace

在 tool execution start/end 挂点记录：

```text
skill read normalized: runId=... raw=~/.../SKILL.md normalized=/abs/.../SKILL.md match=word-docx
skill read triggered: runId=... skill=word-docx path=/abs/.../SKILL.md
skill read failed: runId=... skill=word-docx path=/abs/.../SKILL.md error=ENOENT
```

判定规则：
- 仅当工具名为 `read_file` 或其 alias，且归一化路径命中“本轮已注入 catalog 的 canonical location 集合”时，才计为 skill read
- 普通文件读取不得误记为 skill read

#### 4.10.4 安全约束

- 绝对路径只允许出现在内部 prompt、内部日志、受控调试接口中
- 任何面向普通终端用户的 UI 不应直接展示原始绝对路径

---

## 5. 代码改动范围

### 5.1 `SkillLoader`

职责调整：
- 从“正文注入器”改为“多源 + 安全 + gating + catalog builder”

必须新增或重构：
- `loadSkillEntries(...)`
- `filterSkillEntries(...)`
- `buildSkillsCatalogPrompt(...)`
- `buildSkillsCompactPrompt(...)`
- `applySkillsPromptLimits(...)`
- `normalizeSkillLocation(...)`
- `resolveSkillReadPath(...)`

### 5.2 `SkillFrontmatterParser`

必须补齐：
- YAML frontmatter 解析
- structured value JSON 字符串化
- inline 冒号保护
- metadata JSON5 解析
- `openclaw + LEGACY_MANIFEST_KEYS`
- install 安全校验
- invocation policy 默认值合同

### 5.3 `SkillConfigResolver` / `SkillEnvOverrides`

必须对齐：
- config lookup key 使用 `skillKey ?? name`，并保留 legacy directory key fallback
- `primaryEnv` / `apiKey` 映射基于新 key 生效
- `SkillConfigResolver.shouldIncludeSkill(...)` 成为 prompt 前 eligibility 的唯一事实源，`SkillLoader.filterSkillEntries(...)` 不再维护平行规则

### 5.4 `OpenClawConfig`

必须新增：
- `SkillsLimitsConfig`
- `SkillsConfig.limits`
- 默认值与 TS 当前常量保持一致
- 若序列化/反序列化需要兼容旧配置，`limits` 必须是可选字段

### 5.5 `AgentRunner`

必须调整：
- 不再把 skill 正文拼进 `skillsPrompt`
- `skillsPrompt` 改为 catalog XML
- `AgentRunContext` 新增 run-scoped skills state，至少包含：
  - `resolvedSkills`
  - `injectedSkillLocations`
  - `skillsPromptMode`
  - `skillsTraceEnabled` 或等价 trace 开关
- run-scoped prompt build trace 在这里产出

### 5.6 `ToolRegistry` / alias 层

必须新增：
- 有限 alias 注册，覆盖 `read` / `write` / `grep` / `ls` / `bash`
- alias 与 canonical tool 共用同一 policy 和 audit 路径

### 5.7 `ToolHandlers` / runtime trace

必须新增：
- skill read 的 start/end trace
- raw path -> normalized path -> matched skill 的判定逻辑
- 普通文件读取排除逻辑

### 5.8 `SkillRefresh`

必须对齐：
- watch roots 与 discovery roots 保持一致
- 迁移期内同时 watch `<workspace>/skills` 与 `<workspace>/.openclaw/skills`
- 纳入 managed、extraDirs、plugin dirs、`~/.agents/skills`、`<workspace>/.agents/skills`
- watch path 变化后仍保持 snapshot version bump 语义不变

### 5.9 `SessionChannelMethodRegistrar.handleSkillsStatus`

必须从空壳改为真实结构：
- workspace dir
- managed skills dir
- limits
- skills static status report

---

## 6. 测试与验收

### 6.1 测试层级

| 主题 | 层级 | 目标 |
|---|---|---|
| Loader / discovery / safety | 单元测试 | 覆盖多源优先级、越界路径、symlink、oversized、空目录、空 description |
| eligibility gating | 单元测试 | 覆盖 `enabled`、allowlist、`os`、`requires.*`、`disableModelInvocation` |
| frontmatter / metadata | 单元测试 | 覆盖 YAML、JSON5、inline 冒号、install 非法项丢弃、默认值 |
| prompt build | 单元测试 | 覆盖 full / compact / truncated / XML escaping / warning overhead |
| path normalization | 单元测试 | 覆盖 absolute / `~` / `$HOME` / `${HOME}` / relative |
| `skills.status` | 集成测试 | 覆盖真实返回结构、字段脱敏、eligibility 结果 |
| skill read trace | 集成测试 | mock LLM 返回 `read_file`，验证 trace 正确命中且普通读文件不误判 |
| 模型行为评测 | 统计评测 | 观察命中率、读取率、额外延迟，不作 deterministic 门槛 |

### 6.2 必做单元测试清单

#### 6.2.1 Loader / filtering

- 多源优先级：同名 skill 覆盖正确
- legacy `.openclaw/skills` 与新 `skills/` 冲突时，新目录胜出
- `enabled=false`、bundled allowlist、`os`、`requires.*` 生效
- `disableModelInvocation=true` 不进入 prompt-facing catalog
- symlinked `SKILL.md` 被拒绝
- 越界路径被拒绝
- oversized `SKILL.md` 被拒绝
- frontmatter 缺 description 时跳过
- metadata 解析失败时 skill 保留、metadata 置空

#### 6.2.2 Prompt 构建

- full format 输出 `name + description + location`
- compact format 输出 `name + location`
- truncated format 使用稳定前缀截断
- XML escaping 覆盖 `<>&"'`
- compact warning overhead 150 字符被预留
- Java prompt 中默认出现绝对路径，不依赖 `~/...`

#### 6.2.3 路径归一化

- `~/...`、`$HOME/...`、`${HOME}/...` 归一到同一 canonical path
- 相对路径在 skill 场景按 `baseDir` 解析
- 同一文件不同写法与 catalog `location` 匹配一致

### 6.3 必做集成测试清单

#### 6.3.1 `skills.status`

- 返回真实 skill 列表，而不是空壳
- `eligible` / `blockedByAllowlist` / `missing` / `install` 结果正确
- `disableModelInvocation` 与 `userInvocable` 字段可见

#### 6.3.2 runtime trace

- 模拟模型先触发 `read_file(path=<skill location>)`
- trace 中出现 `skill read normalized` 与 `skill read triggered`
- 随机读取普通文件时不出现 skill read trace
- `read_file` 失败时出现 `skill read failed`

### 6.4 行为验收口径

以下条目是**确定性验收**：

1. 首轮 prompt 中不再出现 skill 正文
2. 命中 skill 时 runtime 能正确识别 skill read
3. compact/truncated 规则和 warning 稳定可复现
4. `skills.status` 可解释“为什么某个 skill 没进 prompt”

以下条目是**统计评测**，不作为 deterministic 发布门槛：

1. 模型是否“首轮一定先读 skill”
2. skill 命中率提升幅度
3. progressive disclosure 带来的延迟增量

### 6.5 延迟基线

Phase B 完成后必须记录一组基线数据：

- 不读 skill 的平均首包时间
- 需要 `read_file` 的平均首包时间
- skill 读取链路带来的额外轮次耗时

该数据只用于后续优化，不影响第一版功能上线。

---

## 7. 实施顺序

### Phase A：Loader、发现、过滤、安全基线

目标：先确保“送进 prompt 的 skill 集合是对的”。

交付物：
- 多源发现
- source precedence
- loader 安全合同
- eligibility gating
- `disableModelInvocation` 生效
- `name` / `skillKey` / legacy directory key 迁移策略落地
- Loader / filtering / frontmatter 单测

### Phase B：catalog prompt、路径归一化、budget 策略

目标：把 prompt 从正文注入切换到 progressive disclosure 的 catalog 模式。

交付物：
- full / compact / truncated prompt builder
- XML escaping
- canonical absolute `location`
- `read_file` 路径归一化
- prompt 构建单测
- 延迟基线测量

### Phase C：观测面与状态接口

目标：让问题可诊断，而不是“改了但看不见”。

交付物：
- `skills.status` 真实实现
- prompt build run trace
- skill read trace
- 相关集成测试

### Phase D：迁移兼容与生态复用

目标：处理 TS skill 复用与 Java 历史包袱，不阻塞主链路上线。

交付物：
- 有限 tool alias 注册
- legacy `.openclaw/skills` 和 legacy directory key 的迁移提示
- skill 作者文档更新

执行关系：
- Phase A 必须先完成
- Phase B 与 Phase C 在 Phase A 完成后可并行推进
- Phase D 放在主链路稳定后执行

---

## 8. 风险与取舍

### 8.1 模型可能不主动读取 skill

这是 progressive disclosure 的固有风险，不是 Java 独有问题。

缓解策略：
- 提高 `description` 的可判别性
- 在 prompt 中明确“匹配时先用 `read_file`”
- 后续可补 `/skill:name` 或 direct dispatch 强制通道，但不纳入本方案第一版

### 8.2 绝对路径会使 compact/truncated 更早触发

这是 Java 为“路径可达性”付出的代价。

处理策略：
- 保持 `location` 为 canonical 绝对路径
- 保持 budget 可配置
- 如果后续实测 budget 压力明显，再讨论仅在 display 层做路径压缩，而不是改变运行时主键

### 8.3 TS skill 生态复用的兼容面仍需克制

tool alias 可以解决一部分 skill 正文工具名问题，但不应无限扩展。

处理策略：
- 只注册语义完全一致的 alias
- 任何新增 alias 都需有技能语料依据与测试覆盖

### 8.4 历史宽松兼容会被 TS parity 收紧

这不是副作用，而是本次对齐的目的之一。

处理策略：
- 核心链路以 TS parity 为准
- 历史行为仅通过显式兼容开关或迁移期 fallback 保留
- 不再把私有扩展默认混入核心解析链路

---

## 9. 最终建议

Java 应对齐 TS 的核心，不是某几行 prompt 文案，而是以下行为模型：

1. **先知道有哪些 skill**：多源发现、去重、过滤、安全校验后，得到 prompt-facing catalog。
2. **再按需读取 skill 正文**：只有模型判断任务相关时，才通过 `read_file(location)` 读取 `SKILL.md`。
3. **读完正文再执行具体工具**：skill 正文不再默认占据系统提示预算。
4. **所有边界情况都可解释**：`skills.status`、prompt build trace、skill read trace 能解释“为什么注入/为什么没注入/为什么没读取”。

收敛后的实施原则是：

- **先修正数据面，再修正 prompt 面**
- **先保证确定性合同，再看模型行为统计**
- **先保证路径与加载安全，再谈 token 微优化**
- **先把 Java 自己的兼容策略写清楚，再谈复用 TS skill 生态**

按这个版本执行，Java 才算真正从“正文直注入”迁移到“目录注入 + 按需读取”的渐进式披露模式，而不是只做了一次 prompt 改写。
