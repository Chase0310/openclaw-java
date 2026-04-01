# 通用模式示例

下面不是具体场景示例，而是可复用的结构模式。

## 模式 1：列表筛查 -> 单项跟进

适用场景：
- 先从一批记录中找出异常项
- 再对某一项继续处理

### 上游 skill

```markdown
1. 调用 `entity_intake_tool`，传入 `action=list_in_range`。
2. 如果返回 `records`，先汇总事实。
3. 如果返回 `handoff`，说明为什么需要进入下一步。
4. 直接复用 `handoff.params`，不要重复追问。
```

### 下游 skill

```markdown
1. 如果上下文已有 `handoff.params`，直接复用。
2. 调用 `entity_followup_tool`，传入 `action=followup_plan`。
3. 先读 `facts`，再读 `suggestions`，最后读 `recommendedNextAction`。
```

## 模式 2：概览 -> 决策

适用场景：
- 先拿总览
- 再按用户追问进入具体决策

```markdown
1. 先调用 `domain_resolution_tool` 的 `action=summary`。
2. 如果用户继续追问具体策略，再调用 `action=decision`。
3. `summary` 负责说明现状，`decision` 负责给建议。
```

## 模式 3：查无结果 -> 补充信息

```markdown
1. 如果 `status=not_found` 或 `found=false`，明确说明未找到。
2. 如果同时返回 `recommendedNextAction`，可提示用户补充必要参数。
3. 不要编造任何不存在的记录或结论。
```
