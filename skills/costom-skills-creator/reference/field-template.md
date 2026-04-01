# 字段模板

这一份模板专门回答“字段模板里要不要增加每个字段的罗列、作用、注释和枚举说明”。结论是：要，而且应该写清楚字段的语义、是否必需、典型用途、回复方式，以及字段注释；如果字段是枚举类型，还要给出可选值与每个值的含义。

## 推荐字段总览

| 字段 | 是否推荐 | 类型 | 作用 | 什么时候用 | skill 怎么用 | 字段注释 |
|------|----------|------|------|------------|---------------|----------|
| `action` | 建议必填 | `string` | 标识当前 tool 子动作 | 一个 tool 有多个 action 时 | 用来判断当前结果对应哪个处理分支 | 当前调用使用的动作名 |
| `status` | 建议必填 | `enum<string>` | 标识当前结果状态 | 所有结构化返回 | 先判断命中、未命中、承接、待补充 | 当前结果属于哪一类处理状态 |
| `found` | 建议必填 | `boolean` | 标识是否命中对象或数据 | 查询、查找、匹配类场景 | 与 `status` 一起判断是否命中 | 是否找到了目标对象或目标数据 |
| `message` | 建议必填 | `string` | 当前结果的简要说明 | 所有返回 | 作为首句概括 | 给模型和用户看的摘要说明 |
| `record` | 按需 | `object` | 单条事实数据 | 单对象返回 | 提取单条事实 | 单个对象的结构化明细 |
| `records` | 按需 | `array<object>` | 多条事实数据 | 列表或批量返回 | 汇总数量、状态、差异 | 一组对象的结构化列表 |
| `facts` | 推荐 | `object` | 显式标记事实信息 | 需要区分事实与建议时 | 先按事实输出 | 已确认的事实字段集合 |
| `suggestions` | 推荐 | `object` | 显式标记建议信息 | 需要给建议或推荐动作时 | 明确表述为建议，不当作事实 | 建议、推荐做法或候选动作 |
| `warnings` | 可选 | `array<string> | array<object>` | 风险、限制、注意事项 | 有边界条件或风险提醒时 | 作为补充提示输出 | 风险提示、限制说明或注意事项 |
| `recommendedNextAction` | 强烈建议 | `string` | 下一步建议 | 存在后续动作时 | 用“下一步建议”明确表达 | 推荐继续执行的下一步 |
| `handoff` | 按需 | `object` | 下一 skill 的承接信息 | 多 skill 串联时 | 说明切换原因并复用 `handoff.params` | 传给下一个 skill 的目标、原因与参数 |

## 推荐字段定义模板

当 creator 为某个 tool 或 skill 生成字段说明时，建议每个字段都至少按下面的结构列出：

```markdown
- 字段名：`status`
- 类型：`enum<string>`
- 是否必填：是
- 作用：标识当前结果状态，决定后续回复与是否需要承接。
- 字段注释：结果状态码。
- 枚举值：
  - `ok`：正常命中，可继续读取事实字段。
  - `not_found`：正常未命中，不应当作系统异常。
  - `handoff`：当前结果需要切换到下一个 skill 或处理阶段。
  - `needs_input`：还缺少关键输入，当前不能完成处理。
  - `partial`：返回了部分结果，但仍有缺失或限制。
- skill 消费方式：先结合 `found` 判断状态，再决定读取事实、建议还是承接字段。
```

## 字段写法要求

1. 每个关键字段都要有“字段注释”，不能只写字段名。
2. 如果字段是枚举类型，必须显式给出“枚举值列表 + 每个值的含义”。
3. 如果字段是对象或数组对象，至少说明内部关键子字段。
4. 如果字段会影响回复顺序、承接逻辑或错误判断，要写出 skill 的消费方式。
5. 如果字段是建议类字段，必须注明“这是建议，不是已执行事实”。

## 枚举字段写法建议

### `status`

推荐值：
- `ok`：正常命中，可继续读取事实字段。
- `not_found`：正常未命中，不应编造结果。
- `handoff`：需要承接到下一个 skill 或下一步处理。
- `needs_input`：缺少关键参数，需要补充信息。
- `partial`：已返回部分结果，但结果不完整。

不要把系统异常伪装成 `status=error`；真正失败更适合走 `success=false` 与 `error`。

### 其他枚举字段

如果某个字段本身也是枚举，例如：
- `decision`
- `category`
- `priority`
- `channel`

建议也按相同方式列出：

```markdown
- 字段名：`priority`
- 类型：`enum<string>`
- 字段注释：当前对象的优先级。
- 枚举值：
  - `high`：高优先级，需要尽快处理。
  - `medium`：中优先级，按正常顺序处理。
  - `low`：低优先级，可延后处理。
```

## 对象字段写法建议

### `record` 与 `records`

1. `record` 用于单条对象。
2. `records` 用于多条对象。
3. 两者不必同时强制出现，但至少要让 skill 能读懂核心事实。
4. 如果对象里有关键子字段，应该继续列出子字段注释。

示例：

```markdown
- 字段名：`record`
- 类型：`object`
- 字段注释：单条对象详情。
- 子字段：
  - `id`：对象唯一标识。
  - `name`：对象名称。
  - `status`：对象当前状态。
```

### `facts` 与 `suggestions`

推荐分层：
- `facts` 放当前已知事实
- `suggestions` 放建议、推荐做法、候选动作

这能让 skill 明确执行“先事实，后建议”的回复规则。

### `handoff`

推荐结构：

```json
{
  "handoff": {
    "targetSkill": "next-skill",
    "reason": "为什么要切换",
    "params": {
      "key": "value"
    }
  }
}
```

推荐注释方式：
- `targetSkill`：下一个要切换到的 skill 名称。
- `reason`：切换原因。
- `params`：继续处理时可以直接复用的参数。

使用要求：
1. `reason` 要能解释为什么切换。
2. `params` 要足够让下一个 skill 直接继续。
3. 已有 `params` 不要重复追问。

## 回复映射模板

```markdown
字段消费顺序：
1. `status` + `found`
2. `message`
3. `record` / `records` / `facts`
4. `suggestions` / `warnings`
5. `recommendedNextAction` / `handoff`

回复组织顺序：
1. 先说结果状态
2. 再说事实
3. 再说建议
4. 最后说下一步
```

## 完整示例

下面是一份可直接复用的字段说明示例，覆盖了普通字段、枚举字段、对象字段和承接字段。

```markdown
字段说明：

- 字段名：`action`
- 类型：`string`
- 是否必填：是
- 作用：标识当前调用使用的 tool 子动作。
- 字段注释：动作名。
- skill 消费方式：先根据 `action` 判断当前结果属于哪个处理分支。

- 字段名：`status`
- 类型：`enum<string>`
- 是否必填：是
- 作用：标识当前结果状态。
- 字段注释：结果状态码。
- 枚举值：
  - `ok`：正常命中，可继续读取事实字段。
  - `not_found`：正常未命中，不应编造结果。
  - `handoff`：需要进入下一步处理或切换到下一个 skill。
  - `needs_input`：缺少关键输入，当前无法完成处理。
  - `partial`：返回了部分结果，但结果仍不完整。
- skill 消费方式：先结合 `found` 判断当前是命中、未命中还是承接场景。

- 字段名：`found`
- 类型：`boolean`
- 是否必填：是
- 作用：标识是否命中目标对象或目标数据。
- 字段注释：是否命中。
- skill 消费方式：如果 `found=false`，优先进入未命中规则，不继续编造事实。

- 字段名：`message`
- 类型：`string`
- 是否必填：是
- 作用：提供本次结果的简要摘要。
- 字段注释：结果摘要。
- skill 消费方式：作为回复首句，用来概括工具执行结果。

- 字段名：`record`
- 类型：`object`
- 是否必填：否
- 作用：承载单条对象的结构化详情。
- 字段注释：单条对象详情。
- 子字段：
  - `id`：对象唯一标识。
  - `name`：对象名称。
  - `status`：对象当前状态。
  - `priority`：对象优先级。
- skill 消费方式：提取单条事实信息，先说对象现状，再说建议。

- 字段名：`priority`
- 类型：`enum<string>`
- 是否必填：否
- 作用：标识当前对象的优先级。
- 字段注释：优先级等级。
- 枚举值：
  - `high`：高优先级，需要尽快处理。
  - `medium`：中优先级，按正常顺序处理。
  - `low`：低优先级，可延后处理。
- skill 消费方式：用于决定回复里的处理顺序和紧急程度。

- 字段名：`facts`
- 类型：`object`
- 是否必填：否
- 作用：承载已经确认的事实信息。
- 字段注释：事实字段集合。
- 子字段：
  - `currentState`：当前状态说明。
  - `matchedCount`：命中数量。
- skill 消费方式：优先从这里提取客观事实，不与建议混写。

- 字段名：`suggestions`
- 类型：`object`
- 是否必填：否
- 作用：承载建议、推荐动作或候选方案。
- 字段注释：建议字段集合。
- 子字段：
  - `recommendedPlan`：推荐方案。
  - `recommendedReason`：推荐原因。
- skill 消费方式：明确说成“建议”或“推荐”，不要说成已执行完成。

- 字段名：`recommendedNextAction`
- 类型：`string`
- 是否必填：否
- 作用：给出下一步推荐动作。
- 字段注释：下一步建议。
- skill 消费方式：放在回复结尾，作为下一步动作说明。

- 字段名：`handoff`
- 类型：`object`
- 是否必填：否
- 作用：承载切换到下一个 skill 所需的信息。
- 字段注释：承接信息。
- 子字段：
  - `targetSkill`：下一个 skill 名称。
  - `reason`：切换原因。
  - `params`：继续处理时直接复用的参数。
- skill 消费方式：先解释为什么切换，再复用 `handoff.params`，不要重复追问。
```

### 对应 payload 示例

```json
{
  "action": "summary",
  "status": "handoff",
  "found": true,
  "message": "已找到目标对象，并建议进入下一步处理。",
  "record": {
    "id": "item_001",
    "name": "示例对象",
    "status": "active",
    "priority": "high"
  },
  "facts": {
    "currentState": "对象当前可继续处理",
    "matchedCount": 1
  },
  "suggestions": {
    "recommendedPlan": "进入 followup 流程",
    "recommendedReason": "当前对象需要进一步处理"
  },
  "recommendedNextAction": "切换到 followup skill 并继续处理。",
  "handoff": {
    "targetSkill": "generic-followup-skill",
    "reason": "当前结果已经满足下一步处理条件",
    "params": {
      "itemId": "item_001",
      "priority": "high"
    }
  }
}
```
