# Skill 消费输出规范

本文档说明：skill 在编写和改造时，应该如何稳定消费工具输出。

## 核心结论

skill 应假定自己稳定能读到的是：
- 成功时的 `output`
- 失败时的 `error`

因此 skill 中的返回值说明，必须围绕 `output` 中可见字段来写。

## 基本原则

### 只依赖稳定字段

优先围绕这些字段写规则：
- `status`
- `found`
- `message`
- `record` / `records`
- `facts`
- `suggestions`
- `warnings`
- `recommendedNextAction`
- `handoff`

不要依赖：
- 运行时内部对象类型
- 只存在于 `data` 的隐式字段
- 代码里才能看到、模型看不到的中间结构

### 推荐读取顺序

1. 先看 `status`
2. 再看 `found`
3. 再看 `message`
4. 再看 `record` / `records` / `facts`
5. 最后看 `suggestions`、`recommendedNextAction` 与 `handoff`

### 事实与建议分层

1. 先描述事实字段
2. 再描述建议字段
3. 最后描述下一步动作

## skill 正文建议显式覆盖的规则

1. 触发条件
2. 调用哪个 tool / action
3. 哪些参数可以复用上下文
4. 返回结果解读规则
5. 未命中规则
6. 错误规则
7. 回复规则
8. handoff 规则（如有）

## handoff 规则

如果返回中包含：

```json
{
  "status": "handoff",
  "handoff": {
    "targetSkill": "next-skill",
    "reason": "xxx",
    "params": {}
  }
}
```

skill 应明确：
1. 为什么进入下一步处理
2. 会带过去哪些上下文
3. 直接复用 `handoff.params`
4. 不要重复追问 `handoff.params` 里已有字段
