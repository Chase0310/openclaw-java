---
name: takeout-refund-resolution-demo
description: 使用 takeout_refund_resolution 工具处理从订单分诊步骤带入的复杂退款和赔付上下文
metadata: |
  {
    openclaw: {
      skillKey: "takeout-refund-resolution-demo"
    }
  }
---

# 外卖退款处理演示

当上下文里已经带有来自订单分诊的一组复杂承接参数，或者用户已经明确提供了等价的结构化信息时，使用这个 Skill。

参考资料：
- 当需要查看复杂承接参数结构时，读取 `references/complex-handoff.md`
- 当需要查看字段含义和示例结果时，读取 `references/examples.md`

工作流程：
1. 优先复用订单分诊步骤已经整理好的复杂参数，不要重新追问。
2. 先调用 `takeout_refund_resolution`，传入 `action=resolution_summary`，并一并带上完整复杂参数，得到这一批异常订单的处理概览。
3. 如果用户继续问“每单怎么赔”“最终处理建议是什么”，再调用 `takeout_refund_resolution`，传入 `action=resolution_decision`，并继续带上完整复杂参数。
4. 优先读取工具 `output` 中可见的稳定字段，不要依赖 Java 内存对象。先看 `status`、`found`、`message`，再看 `record`、`records`、`facts`、`suggestions` 与 `recommendedNextAction`。
5. 如果上下文里只有一个简单的 `orderId`，不要假装已经有复杂退款上下文；这种情况更适合先走订单分诊，或者切到配送安抚。
6. 如果 `status=not_found` 或 `found=false`，明确说明没有可处理的异常订单演示数据。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是命中、未命中还是需要用户补充信息。
2. 如果返回中有 `message`，优先用它概括本次售后处理结果。
3. 如果返回中有 `record`、`records`、`flaggedOrders`、`resolutions` 或 `facts`，据此提取事实信息，例如异常订单、问题类型、配送状态、金额。
4. 如果返回中有 `suggestions`、`recommendedNextAction`、`finalSummary` 或逐单处理建议，把它们明确表达为建议，不要当作已经执行完成的事实。
5. 如果返回中同时包含事实字段和建议字段，先描述事实，再描述建议。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明没有可处理的异常订单演示数据。
2. 不要编造不存在的退款金额、赔付策略或订单问题。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成已经给出了退款建议。

回复规则：
- 用自然中文先给整体概览，再给逐单建议。
- 明确区分事实字段和建议字段。
  事实例如 `issueType`、`deliveryStatus`、`amount`。
  建议例如 `suggestedAction`、`suggestedAmount`。
- 明确告诉用户，这些结果来自本地 Java 业务服务的演示数据。
