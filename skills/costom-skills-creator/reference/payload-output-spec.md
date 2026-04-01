# ToolResult 输出规范

本文档说明：通用 creator 在改造 skill 时，应该如何约束工具结果，使 skill 能稳定消费返回值。

## 核心结论

当前 runtime 中，模型稳定可见的是：
- 成功时的 `output`
- 失败时的 `error`

`data` 可以保存结构化对象，但不应承载只有它自己知道的关键信息。

## ToolResult 字段语义

| 字段 | 作用 | 使用原则 |
|------|------|----------|
| `success` | 表示执行层是否成功 | 只表示调用是否成功，不代表领域结论 |
| `output` | 给模型看的主结果文本 | 必须覆盖 skill 需要消费的关键字段 |
| `data` | 结构化对象承载区 | 可以保留对象，但不能只把关键语义放这里 |
| `error` | 失败时的错误说明 | 需要能区分参数问题与系统异常 |

## 推荐 payload 结构

成功结果的 `output` 和 `data` 推荐保持同构，至少优先考虑这些字段：

```json
{
  "action": "string",
  "status": "ok | not_found | handoff | needs_input | partial",
  "found": true,
  "message": "对本次结果的简要说明",
  "record": {},
  "records": [],
  "facts": {},
  "suggestions": {},
  "warnings": [],
  "recommendedNextAction": "下一步建议",
  "handoff": {
    "targetSkill": "next-skill",
    "reason": "为什么需要切换",
    "params": {}
  }
}
```

## 成功、未命中、失败的边界

### 成功命中

建议：
- `success=true`
- `output=payload 的可读文本或 JSON 文本`
- `status=ok`
- `found=true`

### 正常未命中

建议：
- `success=true`
- `status=not_found`
- `found=false`
- 在 `message` 中明确说明未命中原因

### 参数问题或系统异常

建议：
- `success=false`
- `error` 写明错误原因
- 不要把失败伪装成“查到了结果”
