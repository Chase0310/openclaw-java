---
description: 使用 takeout_refund_resolution 工具处理从订单分诊步骤带入的复杂退款和赔付上下文
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
4. 如果上下文里只有一个简单的 `orderId`，不要假装已经有复杂退款上下文；这种情况更适合先走订单分诊，或者切到配送安抚。
5. 如果工具返回未找到记录，明确说明没有可处理的异常订单演示数据。

回复规则：
- 用自然中文先给整体概览，再给逐单建议。
- 明确区分事实字段和建议字段。
  事实例如 `issueType`、`deliveryStatus`、`amount`。
  建议例如 `suggestedAction`、`suggestedAmount`。
- 明确告诉用户，这些结果来自本地 Java 业务服务的演示数据。
