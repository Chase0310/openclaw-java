# Skill 消费 Tool Payload 规范

本文档约束的是：在当前 OpenClaw Java runtime 中，Skill 应该如何消费业务 Tool 返回的 payload。

适用范围：
- 依赖业务类 Tool 的 Skill
- 有分流、承接、handoff、下一步建议的 Skill
- 需要稳定读取 Tool 返回字段的 Skill

---

## 1. 结论先行

当前 Skill 不能直接读取 Java 内存里的 `ToolResult.data`。

在当前 runtime 中，Skill 实际能稳定消费的是：

- 成功时 Tool 返回的 `output`
- 失败时 Tool 返回的 `error`

因此：

- **Skill 的编写必须围绕 `output` 中可见的 payload 字段来设计**
- **所有希望被 Skill 使用的业务字段，都必须出现在 `output` 文本中**

如果业务 Tool 遵循“`output = payload JSON`，`data = 同一份 payload 对象`”，那么 Skill 就可以稳定围绕字段名写消费规则。

---

## 2. 当前 Runtime 事实

### 2.1 Skill 的本质

当前 Java Skill 的主路径是：

- 读取 `SKILL.md`
- 注入系统提示词
- 引导模型在合适的时候调用 Tool

Skill 不是 Java 类，不直接执行逻辑。

### 2.2 Skill 看到的 Tool 结果是什么

当前 runtime 在 Tool 调用成功后，会把 `ToolResult.output` 放回模型上下文。

如果 Tool 调用失败，则会把 `ToolResult.error` 放回模型上下文。

因此 Skill 应假定自己能消费的是：

- JSON 文本
- 或明确错误文本

而不是 Java 对象。

---

## 3. Skill 编写的基本原则

### 3.1 只依赖稳定可见字段

Skill 中描述返回值时，应只依赖这些稳定字段：

- `status`
- `found`
- `message`
- `record` / `records`
- `facts`
- `suggestions`
- `recommendedNextAction`
- `handoff`

不应依赖：

- Java 内部对象类型
- `ToolResult.data` 的运行时对象形态
- 未出现在 `output` 中的隐式字段

### 3.2 先读状态，再读数据

Skill 消费 Tool 返回时，推荐顺序：

1. 先看 `status`
2. 再看 `found`
3. 再看 `message`
4. 再看 `record` / `records`
5. 最后看 `recommendedNextAction` 或 `handoff`

### 3.3 先描述事实，再描述建议

如果 payload 中既有事实字段，又有建议字段，Skill 应遵循：

1. 先说明事实
2. 再说明建议
3. 再说明下一步动作

---

## 4. Skill 必须覆盖的消费规则

每个依赖业务 Tool 的 Skill，建议在正文中显式覆盖以下内容。

### 4.1 成功命中

Skill 应说明：

- Tool 成功返回后，重点读取哪些字段
- 哪些字段属于事实
- 哪些字段属于建议

推荐写法：

- 先读取 `message`
- 再读取 `record` / `records`
- 如果存在 `recommendedNextAction`，明确说明为下一步建议

### 4.2 未命中

Skill 应说明：

- 如果 `status=not_found` 或 `found=false`
- 应明确告诉用户未找到数据
- 不要编造不存在的业务记录

### 4.3 handoff

Skill 应说明：

- 如果返回中包含 `handoff`
- 应复用 `handoff.params`
- 不要重复追问已经在 `handoff.params` 里的字段
- 应说明为什么要进入下一步处理

### 4.4 失败

Skill 应说明：

- 如果 Tool 返回错误
- 应把错误理解为参数问题或系统异常
- 不要把错误包装成“查到了业务结果”

---

## 5. 推荐的 Skill 消费模板

每个业务 Skill 最好显式写出如下规则。

### 5.1 返回结果解读规则

推荐模板：

```markdown
返回结果解读规则：
1. 先看 `status` 与 `found`，判断是命中、未命中还是需要承接下一步。
2. 如果返回中有 `message`，优先用它概括本次工具结果。
3. 如果返回中有 `record` 或 `records`，据此提取事实信息。
4. 如果返回中有 `recommendedNextAction`，把它明确表达为下一步建议。
5. 如果返回中有 `handoff`，复用 `handoff.params`，不要重复追问。
```

### 5.2 未命中规则

推荐模板：

```markdown
如果工具返回 `status=not_found` 或 `found=false`：
1. 明确说明没有找到对应数据。
2. 不要编造不存在的订单、客户信息或处理建议。
3. 如果返回中包含下一步建议，可提示用户补充信息。
```

### 5.3 错误规则

推荐模板：

```markdown
如果工具执行失败：
1. 把返回错误视为参数或系统问题。
2. 不要把错误描述成业务结论。
3. 仅在确有必要时提示用户补充必填参数或稍后重试。
```

---

## 6. 推荐的事实/建议消费规则

如果 Tool payload 显式区分了事实和建议，Skill 应按如下方式消费。

### 6.1 事实字段

典型事实字段：

- `issueType`
- `deliveryStatus`
- `amount`
- `orderId`
- `customerId`

Skill 规则：

- 事实字段可直接用于描述现状
- 不要把事实字段改写成未经支持的推断

### 6.2 建议字段

典型建议字段：

- `suggestedAction`
- `suggestedAmount`
- `recommendedNextAction`
- `handoff`

Skill 规则：

- 建议字段必须明确描述为“建议”“推荐处理方式”“下一步动作”
- 不要把建议字段说成已经执行完成的事实

---

## 7. handoff 场景的 Skill 规则

当 Tool 返回：

```json
{
  "status": "handoff",
  "handoff": {
    "targetSkill": "xxx",
    "reason": "xxx",
    "params": {}
  }
}
```

Skill 应遵循：

1. 先说明为什么进入下一步处理。
2. 再说明会带过去哪些上下文。
3. 直接复用 `handoff.params`。
4. 不要重复询问已经明确存在于 `handoff.params` 的字段。

这类规则尤其适用于：

- 分诊 -> 退款处理
- 分诊 -> 配送安抚
- 总览 -> 逐单决策

---

## 8. Skill 不应依赖的内容

Skill 不应写成下面这种脆弱形式：

- “从 Java 对象里读取某个字段”
- “依赖某个 ToolResult.data 的具体类名”
- “假设某个字段只存在于内存，不在输出文本里”
- “只凭一句自然语言猜测 Tool 的内部状态”

Skill 应始终围绕：

- payload 中稳定出现的字段名
- 明确的状态值
- 明确的 handoff 参数

来写消费规则。

---

## 9. Skill 与 Payload 的闭环约束

以后新增业务 Skill 时，建议把返回消费规则作为固定章节写进 `SKILL.md`。

推荐最小章节如下：

```markdown
返回结果解读规则：
1. 先看 `status` 与 `found`。
2. 再看 `message`。
3. 用 `record` / `records` 提取事实。
4. 用 `recommendedNextAction` 表达下一步建议。
5. 如果有 `handoff`，直接复用 `handoff.params`。

未命中处理规则：
1. `status=not_found` 或 `found=false` 时，明确说明未找到。
2. 不要编造结果。

错误处理规则：
1. Tool 失败时，把它视为参数或系统问题。
2. 不要把错误包装成业务结论。
```

---

## 10. 最终规范

对所有依赖业务 Tool 的 Skill，统一采用以下规则：

1. Skill 只消费 `output` 中可见的 payload 字段。
2. Skill 不依赖 `data` 的 Java 运行时对象形态。
3. Skill 必须显式覆盖成功、未命中、handoff、失败四类返回分支。
4. Skill 必须先读状态，再读事实，再读建议。
5. Skill 必须区分事实字段和建议字段。
6. 如果 Tool payload 有 `handoff`，Skill 必须复用承接参数，不重复追问。
