# 《Skills 渐进式披露对齐方案》详细评审结论

**评审对象**：`openclaw-java/docs/skills-progressive-disclosure-alignment-plan.md`  
**评审日期**：2026-04-01  
**评审结论**：**方向正确，但当前版本不建议直接开工；补齐阻塞项后再进入实施。**

---

## 1. 总体判断

这份方案抓住了正确方向：

- 要从“`SKILL.md` 正文直注入”切到“catalog 注入 + 按需读取”
- Java runtime loop 本身足够，不需要为 skills 单独重写推理闭环
- `read_file` 作为按需读取入口是正确落点

但文档当前仍停留在“方向文档”层面，还不足以作为“实施文档”直接开工。主要问题不是路线错了，而是 **TS 基线能力没有写全、Java 迁移约束没有写清、测试与观测合同还不够可执行**。

如果按当前文本直接推进，最可能出现的结果是：

- prompt 形态变了，但行为仍未真正对齐 TS
- managed / bundled / extra / `.agents/skills` 等来源仍然缺失
- 不可用 skill 仍然进入 catalog
- compact 行为和截断行为在不同实现者手里出现分叉
- 测试只能验证字符串变化，无法稳定验证运行时行为

---

## 2. 主要结论

### 2.1 方向正确

文档对 TS 技术路线的抽象是成立的：

1. 首轮只注入 skill catalog
2. 模型按需读取 `SKILL.md`
3. 读取后再执行具体工具或业务动作

这条主链路没有问题，值得继续推进。

### 2.2 当前最大问题不是“prompt 怎么写”，而是“能力基线没收齐”

如果目标是“与 TS 原生实现对齐”，那对齐对象不只是 catalog XML 这一层，还包括：

- 多源发现
- eligibility gating
- `disableModelInvocation` / `userInvocable` 语义
- compact / truncated 规则
- loader 安全约束
- status / debugging 能力

当前方案写到了其中一部分，但没有把这些全部纳入第一版约束。

### 2.3 当前文档适合作为方案草案，不适合作为实施规格

建议先修文档，再排开发。否则 Phase A 做完后很可能还要返工一轮“TS 真正对齐”。

---

## 3. 阻塞项

以下问题建议视为阻塞项。没有补齐前，不建议把这份文档直接作为开发实施依据。

### 3.1 Phase A 范围过窄，完成后仍不会真正对齐 TS

文档当前把 Phase A 定义为：

- Java 首轮从正文注入切到 `name + description + location`
- 不改 runtime loop
- 不改 tool 执行协议

这一定义过窄。

当前 Java run path 里，`SkillLoader.resolveSkillSnapshotForRun(...)` 实际只走了 workspace 入口，且过滤逻辑很弱：

- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/skills/SkillLoader.java`
- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/runtime/AgentRunner.java`

而 TS 基线里，prompt 前的 skills 集合已经是：

- 多源合并后的结果
- 按配置 / allowlist / OS / requires / remote eligibility 过滤后的结果
- 去掉 `disableModelInvocation` 后的 prompt-facing 结果

对应 TS 代码：

- `openclaw/src/agents/skills/workspace.ts`
- `openclaw/src/agents/skills/config.ts`

**结论**：如果第一阶段只改 prompt 形态，不同时收齐发现范围和 gating，功能上仍然不算“对齐 TS”。

### 3.2 Loader 安全基线缺失

方案把 `SkillLoader` 描述成“catalog 生成器”，但没有把 TS loader 的安全合同写进来。

TS 当前实际做了这些约束：

- 限制每个根目录的候选扫描数量
- 限制每个 source 的最大 skill 数
- 限制单个 `SKILL.md` 最大字节数
- 校验 realpath 必须仍在 root 下
- 拒绝 symlinked `SKILL.md`
- 对异常路径做显式跳过

对应 TS 代码：

- `openclaw/src/agents/skills/local-loader.ts`
- `openclaw/src/agents/skills/workspace.ts`
- `openclaw/src/agents/skills.loadworkspaceskillentries.test.ts`

当前 Java `SkillLoader` 还是朴素扫描目录、直接 `Files.readString(...)`：

- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/skills/SkillLoader.java`

**结论**：如果不把这些非功能约束写入方案，Java 最多只是“prompt 上像 TS”，不是“运行时能力上像 TS”。

### 3.3 名称语义迁移没有设计清楚

方案写了：

- 顶层 `name` 优先于目录名
- 保留目录名校验和告警

这条方向本身是对的，因为 TS loader 也是 `frontmatter.name ?? 目录名`：

- `openclaw/src/agents/skills/local-loader.ts`

但当前 Java 生态里：

- 文档仍把目录名当稳定 skill 名
- loader 也仍然直接使用目录名
- config lookup、去重、覆盖优先级都建立在当前命名语义之上

相关 Java 代码：

- `openclaw-java/docs/skills-guide.md`
- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/skills/SkillLoader.java`
- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/skills/SkillEnvOverrides.java`

同时 TS 又引入了 `skillKey` 作为配置绑定主键：

- `openclaw/src/agents/skills/frontmatter.ts`
- `openclaw/src/agents/skills/config.ts`

**问题不在“要不要支持 `name`”，而在“Java 现有 skill / config 如何迁移”**。当前文档没有回答：

- 现有 `skills.entries.<key>` 怎么兼容
- 同名 skill 覆盖按 display name 还是按 `skillKey`
- 旧 skill 没有 `name` 时是否继续稳定工作
- 对已有目录名依赖是否要保留过渡期

**结论**：这里需要补一段明确迁移策略，否则实现时一定会分叉。

### 3.4 `disableModelInvocation` 没有进入真正的行为设计

文档在目标 entry 里写了 `disableModelInvocation`，但没有把它落成 catalog 过滤规则。

当前 Java parser 已能解析该字段：

- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/skills/SkillFrontmatterParser.java`

TS 的实际语义是：

- `disableModelInvocation = true` 的 skill 不进入 prompt-facing catalog
- `userInvocable = false` 只影响命令注册，不等于不允许 prompt 侧自动发现

对应 TS 代码：

- `openclaw/src/agents/skills/workspace.ts`
- `openclaw/src/agents/skills/frontmatter.ts`

**结论**：这不是“后续增强项”，而是第一版 catalog 行为的一部分，应明确写入 Phase A。

### 3.5 catalog 序列化规范不完整

当前方案定义了 XML-like catalog，但没有明确要求：

- `name` / `description` / `location` 必须做 XML escaping
- compact / full 的 warning 文案是否稳定
- compact 预算是否需要预留 warning 开销
- 截断是“稳定前缀截断”还是其他策略

TS 当前已经给出了明确基线：

- `escapeXml(...)`
- compact warning 开销预留
- count cap 之后再决定 full / compact / truncated
- compact 仍超预算时对稳定前缀做二分

对应 TS 代码：

- `openclaw/src/agents/skills/workspace.ts`
- `openclaw/src/agents/skills/compact-format.test.ts`

**结论**：这些实现合同应写进文档，而不是留给开发者自行脑补。

### 3.6 验收标准过度依赖单次 LLM 行为

当前文档中最强的行为验收是：

- 首轮先 `read_file`
- 第二轮再调用下游工具

这个要求作为“期望轨迹”可以保留，但不适合作为严格验收标准。因为这类顺序容易受模型差异、provider 差异和提示词微调影响。

TS 的做法更像是：

- 在 system prompt 中强约束“先选 skill，再读 skill”
- 用 prompt 结构测试和运行时集成测试覆盖，而不是要求每次生成完全同一条轨迹

对应 TS 代码：

- `openclaw/src/agents/system-prompt.ts`
- `openclaw/src/agents/system-prompt.test.ts`

**建议**：把验收拆成两层：

- 确定性验收：prompt 内容、status 输出、trace 是否出现
- 统计性验收：多轮评测里 skill 命中率与读取率

---

## 4. 可直接参照 TS 收敛的事项

以下事项无需 Java 自己发明规则，建议直接按 TS 实现收敛。

### 4.1 发现范围与覆盖优先级

TS 当前覆盖的来源包括：

- bundled skills
- managed skills
- config extra dirs
- plugin skill dirs
- `~/.agents/skills`
- `<workspace>/.agents/skills`
- `<workspace>/skills`

覆盖优先级也已经明确：

- `extra < bundled < managed < agents-skills-personal < agents-skills-project < workspace`

对应代码：

- `openclaw/src/agents/skills/workspace.ts`

### 4.2 eligibility gating

TS 的 prompt 前过滤不是“只看 description”，而是先看：

- `enabled`
- bundled allowlist
- `os`
- `requires.bins`
- `requires.anyBins`
- `requires.env`
- `requires.config`
- remote eligibility
- `always`

对应代码：

- `openclaw/src/agents/skills/config.ts`
- `openclaw/src/agents/skills-status.ts`

### 4.3 `name` / `skillKey` / 配置绑定

TS 当前语义：

- prompt-facing `name`：`frontmatter.name ?? 目录名`
- config lookup key：`metadata.skillKey ?? skill.name`

对应代码：

- `openclaw/src/agents/skills/local-loader.ts`
- `openclaw/src/agents/skills/frontmatter.ts`

### 4.4 `disableModelInvocation` / `userInvocable`

TS 当前语义：

- `disableModelInvocation`：从 prompt-facing catalog 过滤掉
- `userInvocable`：只影响 command specs 生成

对应代码：

- `openclaw/src/agents/skills/workspace.ts`

### 4.5 compact / truncated 规则

TS 当前规则：

1. 先应用 `maxSkillsInPrompt`
2. full format fit 就保留 full
3. full 不 fit 就尝试 compact
4. compact fit 就保留全部 skill，但省略 description
5. compact 仍不 fit，就二分前缀大小
6. warning 文案显式写明 compact / truncated 状态

对应代码与测试：

- `openclaw/src/agents/skills/workspace.ts`
- `openclaw/src/agents/skills/compact-format.test.ts`

### 4.6 XML escaping

TS 已经实现并测试：

- `openclaw/src/agents/skills/workspace.ts`
- `openclaw/src/agents/skills/compact-format.test.ts`

这一点应直接照搬，不要重新设计。

### 4.7 `skills.status` 的静态诊断结构

TS `skills.status` 已经能回答：

- 这个 skill 是什么来源
- 是否 bundled
- 是否 disabled
- 是否被 allowlist 阻断
- 是否 eligible
- 缺什么 requirements
- 有哪些 configChecks
- 有哪些 install 选项

对应代码：

- `openclaw/src/agents/skills-status.ts`
- `openclaw/src/gateway/server-methods/skills.ts`

Java 可以在此基础上再加“本轮 promptMode / 本轮注入结果”字段，但不建议只做一个过弱的壳。

---

## 5. 需要 Java 自己做的决策

以下问题 TS 无法直接替 Java 做决定，文档需要单独补齐。

### 5.1 旧 Java skill 的命名兼容策略

如果 Java 现网 skill 长期依赖目录名语义，切到 `frontmatter.name` 优先后，至少要回答：

- 是否允许过渡期同时接受目录名和 `name`
- status / 日志里展示哪个名字
- config lookup 是否只认 `skillKey`
- 何时移除目录名兼容语义

### 5.2 运行时观测面是否扩展为“动态注入结果”

TS 的 `skills.status` 偏静态能力盘点；本方案还想要：

- 本轮 promptMode
- 本轮实际注入了哪些 skill
- 是否 compact / truncated
- 模型是否实际读取了某个 skill

这属于 Java 的额外可观测性设计，应明确：

- 放在哪个 API
- 是 session-scoped 还是 run-scoped
- 生命周期和清理策略是什么

### 5.3 TS 工具名与 Java 工具名的深层兼容

文档已经正确识别了提示词层的 `read` vs `read_file` 差异，但还没处理 skill 正文内部的工具名差异。

如果目标是复用 TS 生态 skill，会遇到类似问题：

- TS skill 可能写 `write`，Java 工具实际叫 `write_file`
- TS skill 可能写 `grep`，Java 工具实际叫 `grep_search`

这不是 progressive disclosure 主链路的 blocker，但它是“生态复用”的重要兼容项。至少应在文档里明确：

- 第一版暂不解决，只支持按 Java 工具名编写的 skill
- 或增加 tool alias
- 或做正文映射层

### 5.4 trace 挂点与数据面

文档想要 `skill read triggered`，这是合理的，但现在没有写清挂点。

当前 Java subscribe handler 里对读工具仍保留 `"read"` 的分支：

- `openclaw-java/openclaw-agent/src/main/java/com/openclaw/agent/runtime/subscribe/ToolHandlers.java`

这里至少要补一条设计决定：

- 在 tool execution start / end 哪一层判定
- 如何拿到“当前 run 注入过的 skill location 列表”
- 如何避免普通 `read_file` 被误记为 skill read

---

## 6. 建议调整后的实施顺序

建议把实施顺序改成下面四步。

### Phase A: Loader 与过滤基线对齐

先对齐：

- 多源发现
- 覆盖优先级
- eligibility gating
- `disableModelInvocation`
- loader 安全约束

如果这一步不先做，后面的 catalog prompt 只是在错误数据上重新排版。

### Phase B: catalog prompt 与 compact 策略对齐

这一阶段再做：

- full catalog
- compact catalog
- truncated 逻辑
- XML escaping
- warning 文案

### Phase C: 观测面与状态接口

这一阶段做：

- `skills.status` 真数据
- prompt build logs
- skill read trace
- run-scoped 调试输出

### Phase D: 迁移兼容与生态复用

最后再做：

- `name` 迁移兼容
- `skillKey` 文档化
- metadata 扩展兼容
- TS 工具名与 Java 工具名兼容策略

---

## 7. 测试建议

当前方案的测试层级写得不够可执行。建议明确补成下面四类。

### 7.1 Loader / filtering 单元测试

覆盖：

- 多源优先级
- allowlist
- `enabled=false`
- `always`
- `os`
- `requires.*`
- `disableModelInvocation`
- symlink / 越界路径 / oversized `SKILL.md`

### 7.2 Prompt 构建单元测试

覆盖：

- full format
- compact format
- truncated format
- XML escaping
- compact warning 开销预留
- canonical path 与 compacted path 的差异

### 7.3 Gateway / status 集成测试

覆盖：

- `skills.status` 返回真实结构
- 不泄漏敏感配置值
- `eligible` / `blockedByAllowlist` / `missing` 结果正确

### 7.4 Runtime trace 集成测试

覆盖：

- 模拟命中 skill 后的 `read_file`
- 确认 trace 正确打出
- 确认普通文件读取不会被误判成 skill read

对于“模型是否首轮先读 skill”这类问题，建议作为评测项，不要作为严格 deterministic 测试。

---

## 8. 最终评审结论

### 8.1 可以保留的判断

以下判断我认为可以保留，不需要推翻：

- Java runtime loop 足够，不需要重写
- 首轮应该从正文注入切到 catalog 注入
- 读取正文应通过 `read_file`
- compact fallback 是必须项
- 观测面必须补

### 8.2 必须修订的部分

以下部分建议修改后再实施：

- Phase A 范围
- loader 章节
- skill 发现路径章节
- compact 规则章节
- `disableModelInvocation` 语义
- status / trace 章节
- 验收章节

### 8.3 最终建议

**建议结论：修订后通过。**

具体含义是：

- 不建议按当前文档直接开工
- 建议先把文档收敛到“TS 基线 + Java 迁移约束 + 可执行测试”
- 文档修订完成后，这条路线可以进入开发

---

## 9. 修订清单

建议作者在原方案中至少补齐以下内容：

1. 在“Java 对齐后的目标行为”中补入 TS 的真实发现范围、gating 规则和 `disableModelInvocation` 过滤规则。
2. 在“需要改动的模块”中新增 loader 安全约束与 limits 设计。
3. 在“compact mode 目标”中补入 XML escaping、warning 文案、预算预留、稳定前缀截断。
4. 在“观测与调试设计”中明确 `skills.status` 是静态能力状态、动态注入状态，还是二者分层暴露。
5. 在“验收标准”中区分确定性验收与统计性评测，不再把单次 LLM 顺序作为唯一硬标准。
6. 在“风险与取舍”中新增 Java 命名迁移风险与工具名兼容风险。
7. 在“分阶段实施建议”中把 loader parity 前移到 prompt 形态切换之前。

