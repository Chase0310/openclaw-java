# Skill 章节模板

下面是一份适合业务化改造的最小章节模板，可按场景删减。

```markdown
工作流程：
1. 如果上下文中已有关键参数，直接复用，不要重复追问。
2. 调用 `tool_name`，传入 `action=xxx` 与必要参数。
3. 优先读取工具 `output` 中可见的稳定字段，不要依赖 Java 内存对象。先看 `status`、`found`、`message`，再看 `record`、`records`、`facts`、`suggestions`、`recommendedNextAction` 与 `handoff`。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是命中、未命中还是需要承接下一步。
2. 如果返回中有 `message`，优先用它概括本次工具结果。
3. 如果返回中有 `record`、`records` 或 `facts`，据此提取事实信息。
4. 如果返回中有 `suggestions`、`recommendedNextAction` 或 `handoff`，把它们明确表达为建议或下一步动作。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明未找到对应数据。
2. 不要编造不存在的业务记录、金额、状态或建议。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成业务结论。

回复规则：
- 用自然中文概括结果。
- 先描述事实字段，再描述建议字段或下一步动作。
- 如果结果里包含 `recommendedNextAction`，把它明确表达为下一步建议。
- 如果结果里包含 `handoff`，先说明为什么切换，再说明会带过去哪些上下文。
```

## 适用提醒

- 批量分诊类 skill：重点补 `handoff` 和批量事实字段。
- 单单处理类 skill：重点补 `facts`、`suggestions` 和话术规则。
- 决策类 skill：重点补“事实字段 vs 建议字段”的边界。
