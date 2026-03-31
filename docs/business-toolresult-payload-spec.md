# 业务类 ToolResult Payload 规范

本文档约束的是**业务类 Tool** 如何在当前 OpenClaw Java runtime 中使用 `ToolResult`。

适用范围：
- 业务查询类 Tool
- 分诊/承接/售后/决策类 Tool
- 任何希望被 Skill 稳定消费的自定义 Tool

不适用范围：
- 纯底层基础工具，例如 `exec`、`read_file`
- 只返回图片或二进制内容的多模态工具

---

## 1. 结论先行

当前 runtime 中，`ToolResult` 的主消费通道是：

- 成功时：`output`
- 失败时：`error`
- 多模态时：`contentParts` + 文本 fallback

`data` 当前不是模型主消费字段。

这意味着：

- **不要只把关键信息放进 `data`**
- **要让 `output` 成为 `data` 的可读文本表示**
- **业务规范应该落在 `payload` 结构上，而不是改动底层 `ToolResult` 类型**

---

## 2. 当前 Runtime 事实

### 2.1 `ToolResult` 的当前定义

当前 `ToolResult` 定义如下：

```java
private boolean success;
private String output;
private Object data;
private String error;
```

代码位置：
- `openclaw-agent/src/main/java/com/openclaw/agent/tools/AgentTool.java`

### 2.2 Runtime 如何消费 `ToolResult`

当前 `AgentRunner` 在 Tool 执行完成后，会把结果重新组织成一条 `role=tool` 的消息回喂模型：

- 成功时，使用 `toolResult.getOutput()`
- 失败时，使用 `toolResult.getError()`
- 如果存在 `contentParts`，则优先传多模态内容，并额外附上文本 fallback

代码位置：
- `openclaw-agent/src/main/java/com/openclaw/agent/runtime/AgentRunner.java`

### 2.3 Provider 最终发送给模型的内容

当前 provider 序列化 Tool 消息时，发送给模型的仍然是：

- `content`
- 或 `contentParts`

不会额外单独发送 `data`。

代码位置：
- `openclaw-agent/src/main/java/com/openclaw/agent/models/OpenAICompatibleProvider.java`
- `openclaw-agent/src/main/java/com/openclaw/agent/models/AnthropicProvider.java`

### 2.4 当前仓库已经隐含的推荐模式

`ToolParamUtils.jsonResult(payload)` 的行为是：

- `output = payload 的 JSON 字符串`
- `data = payload 对象`

这说明当前系统天然适合如下设计：

- `data` 保留结构化 payload
- `output` 使用同一份 payload 的 JSON 文本

代码位置：
- `openclaw-agent/src/main/java/com/openclaw/agent/tools/ToolParamUtils.java`

---

## 3. `ToolResult` 四个字段的语义

### 3.1 `success`

表示**执行层是否成功**，不是业务结论本身。

推荐理解：

- `true`：Tool 调用成功完成，模型继续读取 `output`
- `false`：Tool 调用失败，模型继续读取 `error`

不要把它当成业务结果的唯一表达。

例如：

- “没有找到记录”通常不是执行失败
- “参数缺失”“内部异常”才是执行失败

### 3.2 `output`

这是**给模型看的主结果文本**，是业务 Tool 最重要的字段。

要求：

- 必须能让模型理解本次调用发生了什么
- 必须能让模型知道下一步怎么处理
- 必须覆盖 Skill 要消费的关键字段

推荐：

- 直接放标准化 payload 的 JSON 文本
- 或在自然语言摘要后附 payload JSON

### 3.3 `data`

这是**结构化 payload 的承载字段**。

适合放：

- 原始记录
- 结构化摘要
- 事实字段
- 决策字段
- handoff 参数

注意：

- 当前 runtime 不依赖 `data` 直接驱动模型推理
- 所以不能把关键业务语义只放进 `data`

### 3.4 `error`

这是**失败时给模型和上层看的错误说明**。

要求：

- 可读
- 明确
- 能区分参数问题与系统问题

不要把正常未命中、空结果这类业务分支放进 `error`。

---

## 4. 推荐的 Payload 结构

业务类 Tool 成功时，`data` 推荐使用如下结构：

```json
{
  "action": "string",
  "status": "ok | not_found | handoff | needs_input | partial",
  "found": true,
  "message": "给模型理解当前结果的核心说明",
  "record": {},
  "records": [],
  "facts": {},
  "suggestions": {},
  "warnings": [],
  "recommendedNextAction": "下一步建议",
  "handoff": {
    "targetSkill": "xxx",
    "reason": "xxx",
    "params": {}
  }
}
```

### 4.1 字段说明

| 字段 | 必须 | 说明 |
|------|------|------|
| `action` | 建议必须 | 当前 tool 子动作标识 |
| `status` | 建议必须 | 当前业务状态 |
| `found` | 建议必须 | 是否命中业务记录 |
| `message` | 建议必须 | 给模型理解当前结果的核心说明 |
| `record` / `records` | 按需 | 事实数据 |
| `facts` | 可选 | 明确标记为事实的信息 |
| `suggestions` | 可选 | 明确标记为建议的信息 |
| `warnings` | 可选 | 风险或提示 |
| `recommendedNextAction` | 强烈建议 | 下一步建议 |
| `handoff` | 按需 | Skill 间承接参数 |

### 4.2 `status` 推荐值

推荐使用以下语义：

- `ok`
- `not_found`
- `handoff`
- `needs_input`
- `partial`

如果是系统错误，不建议用 `status=error` 代替 `ToolResult.fail(...)`。

---

## 5. 成功、未命中、失败的边界

### 5.1 成功命中

使用：

- `success=true`
- `output=payload JSON`
- `data=payload`

其中：

- `status=ok`
- `found=true`

### 5.2 正常未命中

这通常不是系统错误，建议仍使用：

- `success=true`
- `output=payload JSON`
- `data=payload`

其中：

- `status=not_found`
- `found=false`
- `message` 明确说明未命中原因

### 5.3 参数非法或系统异常

这才应使用：

- `success=false`
- `error=明确错误信息`

例如：

- 必填参数缺失
- action 非法
- 内部执行异常

---

## 6. `output` 的强约束

### 6.1 强制要求

业务 Tool 的 `output` 必须满足：

1. 不能为空
2. 不能只写 “success”
3. 不能只有一段模糊自然语言而没有结构
4. 不能把关键业务语义只留在 `data`

### 6.2 推荐写法

推荐直接让：

- `output = ToolParamUtils.toJsonString(payload)`

这样可以保证：

- 模型能读到稳定字段
- Skill 可以围绕字段名写消费规则
- 程序侧还能复用同一份 payload

推荐示例：

```java
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("action", "resolution_summary");
payload.put("status", "ok");
payload.put("found", true);
payload.put("message", "已找到 3 笔异常订单。");
payload.put("recommendedNextAction", "进入退款处理。");
payload.put("records", records);

return ToolResult.ok(ToolParamUtils.toJsonString(payload), payload);
```

---

## 7. 推荐的事实/建议分层

对业务 Tool，建议把“事实”和“建议”显式分开。

推荐做法：

- 事实字段放在 `record` / `records` / `facts`
- 建议字段放在 `suggestions` / `recommendedNextAction` / `handoff`

例如：

事实：

- `issueType`
- `deliveryStatus`
- `amount`

建议：

- `suggestedAction`
- `suggestedAmount`
- `recommendedNextAction`

这样 Skill 更容易写出“先描述事实，再给建议”的稳定规则。

---

## 8. 业务 Tool 的标准返回模板

### 8.1 成功命中模板

```json
{
  "action": "order_overview",
  "status": "ok",
  "found": true,
  "message": "已找到订单与客户信息。",
  "record": {},
  "recommendedNextAction": "可继续查看履约详情。"
}
```

### 8.2 未命中模板

```json
{
  "action": "order_overview",
  "status": "not_found",
  "found": false,
  "message": "没有找到与该标识匹配的业务记录。",
  "recommendedNextAction": "请确认订单号或客户标识。"
}
```

### 8.3 handoff 模板

```json
{
  "action": "list_orders_in_range",
  "status": "handoff",
  "found": true,
  "message": "检测到需要进入下一步售后处理。",
  "recommendedNextAction": "切换到退款处理。",
  "handoff": {
    "targetSkill": "refund-resolution-demo",
    "reason": "multiple_orders_need_refund_review",
    "params": {}
  }
}
```

### 8.4 执行失败模板

```text
customerId required
```

或：

```text
takeout_refund_resolution 执行失败: xxx
```

---

## 9. 不建议的做法

- 只在 `data` 放结构化结果，`output` 只写一句模糊自然语言
- 把“未找到记录”直接返回为 `fail`
- 把事实和建议混在一堆无语义字段里
- 让不同业务 Tool 的状态字段完全不一致
- 在 `output` 中使用与 `data` 完全不同的字段命名

---

## 10. 最终规范

对所有业务类 Tool，统一采用以下规则：

1. `ToolResult` 结构保持不变，不改 runtime 约定。
2. 成功时必须返回一份标准化业务 payload。
3. `data` 保存该 payload 对象。
4. `output` 保存该 payload 的 JSON 文本。
5. 业务未命中使用 `success=true`，不要误用 `fail`。
6. 真正失败时才使用 `error`。
7. 事实字段与建议字段应显式分层。
8. 所有需要被 Skill 消费的字段，必须出现在 `output` 中。
