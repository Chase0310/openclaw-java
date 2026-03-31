---
description: 将标准 skill 改造成符合业务 ToolResult payload 规范与 Skill 消费规范的业务 skill
---

# 业务 Skill 改造器

当用户要求把一个通用 skill、标准 skill 或演示 skill 改造成业务可用版本，或者要求统一一批 skill 的写法、返回字段消费规则、handoff 规则时，使用这个 Skill。

参考资料：
- 当需要完整理解业务 Tool payload 约束时，读取 `../../docs/business-toolresult-payload-spec.md`
- 当需要完整理解 Skill 如何消费 payload 时，读取 `../../docs/skill-payload-consumption-spec.md`
- 当需要快速执行改造流程时，读取 `references/refactor-checklist.md`
- 当需要套用统一章节模板时，读取 `references/section-template.md`
- 当需要看现成业务化示例时，优先参考 `../takeout-order-issue-intake-demo/SKILL.md`、`../takeout-delivery-followup-demo/SKILL.md`、`../takeout-refund-resolution-demo/SKILL.md`

工作流程：
1. 先识别目标 skill 依赖的 tool、action 和业务场景，不要先改文案。
2. 阅读目标 tool 的参数 schema、返回约定和现有示例，确认模型在 runtime 中真正能稳定看到的是 `output` 与 `error`，而不是 Java 内存对象。
3. 如果业务 tool 已遵循 payload 规范，围绕 `status`、`found`、`message`、`record`、`records`、`facts`、`suggestions`、`recommendedNextAction`、`handoff` 这些稳定字段改写 skill。
4. 如果目标 skill 仍在描述 Java 对象、内部类或隐式字段，统一改成“读取工具 `output` 中可见的稳定字段”，不要让 skill 依赖运行时内存结构。
5. 为每个业务 skill 至少补齐这些章节或等价规则：工作流程、返回结果解读规则、未命中规则、错误规则、回复规则。
6. 如果 payload 里有 `handoff`，skill 必须说明如何复用 `handoff.params`，并明确禁止重复追问已有字段。
7. 如果 payload 里同时存在事实字段和建议字段，skill 必须说明“先描述事实，再描述建议或下一步动作”。
8. 如果 tool schema、payload 结构或 action 语义不清楚，先要求补充或从代码中查证，不要编造字段。

改造规则：
- 只依赖稳定可见字段，不依赖 Java 内部对象类型。
- 先看 `status` 与 `found`，再看 `message`，再看 `record` / `records` / `facts`，最后看 `recommendedNextAction` 或 `handoff`。
- 正常未命中要写成业务分支，不要当成系统错误。
- Tool 失败要明确视为参数问题或系统异常，不要包装成业务结论。
- 回复规则中要明确哪些内容来自本地业务服务、哪些是建议字段。

输出要求：
- 优先直接修改目标 `SKILL.md`，不要只给口头建议。
- 如果存在多个同类 skill，统一它们的章节结构和字段消费方式。
- 除非用户明确要求，否则不要额外生成 README、CHANGELOG 或安装说明。
