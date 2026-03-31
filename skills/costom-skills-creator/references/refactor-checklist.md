# Skill 业务改造清单

## 改造前检查

1. 找出目标 skill 调用的 tool 名和 action。
2. 查 tool schema，确认必填参数、可复用参数、上下文承接参数。
3. 查 tool 返回结构，确认稳定字段是否已出现在 `output` 中。
4. 如果 tool 只在 `data` 中放结构化信息，没有出现在 `output` 中，先记录这一风险。

## Skill 正文必须补齐的规则

1. 何时触发。
2. 调哪个 tool / action。
3. 哪些参数可以直接复用上下文。
4. 返回结果解读规则。
5. 未命中规则。
6. 错误规则。
7. 回复规则。

## 返回结果解读规则的最小要求

1. 先看 `status` 与 `found`。
2. 再看 `message`。
3. 再看 `record`、`records`、`facts`。
4. 最后看 `recommendedNextAction`、`suggestions`、`handoff`。

## handoff 场景

如果返回中有：

```json
{
  "handoff": {
    "targetSkill": "xxx",
    "params": {}
  }
}
```

则 skill 必须写清楚：

1. 为什么要进入下一步处理。
2. 会带过去哪些上下文。
3. 直接复用 `handoff.params`。
4. 不重复追问已有字段。

## 事实与建议分层

常见事实字段：
- `orderId`
- `customerId`
- `issueType`
- `deliveryStatus`
- `amount`

常见建议字段：
- `suggestedAction`
- `suggestedAmount`
- `recommendedNextAction`
- `handoff`

Skill 应明确：

1. 先描述事实。
2. 再描述建议或下一步动作。
3. 不要把建议说成已执行完成的事实。
