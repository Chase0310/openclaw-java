---
description: 使用 takeout_order_issue_intake 工具按时间范围筛查多笔外卖订单，并根据问题类型切换到退款处理或配送安抚
---

# 外卖订单分诊演示

当用户说“最近几天的外卖订单里哪些有问题”“帮我看看这段时间哪些订单需要售后”时，使用这个 Skill。

参考资料：
- 当需要看分流规则和 handoff 字段时，读取 `references/routing-rules.md`
- 当需要看演示时间范围和示例输出时，读取 `references/examples.md`

可用示例时间范围：
- `MORNING_OK`
- `LUNCH_TODAY`
- `DINNER_DELAY`
- `LAST_3_DAYS`

工作流程：
1. 调用 `takeout_order_issue_intake`，并传入 `action=list_orders_in_range` 与 `timeRangeKey`。
2. 如果工具返回 `record.triageDecision.decision=all_clear`，直接汇总这一时间段内的订单情况，不要跳转。
3. 如果工具返回 `record.handoff.targetSkill=takeout-refund-resolution-demo`，说明需要进入退款或赔付处理。
4. 跳到退款处理时，把 `record.handoff.params` 当作复杂承接参数，不要重复追问。字段结构见 `references/routing-rules.md`。
5. 如果工具返回 `record.handoff.targetSkill=takeout-delivery-followup-demo`，说明需要进入配送安抚处理。
6. 跳到配送安抚时，把 `record.handoff.params` 当作简单承接参数，通常只需要继续使用 `orderId`。字段结构见 `references/routing-rules.md`。
7. 如果工具返回未找到记录，明确说明没有这个时间范围的演示数据。

回复规则：
- 用自然中文概括结果，并说明数据来自本地 Java 业务服务。
- 如果发生跳转，先说明为什么要切换到下一步处理，再说明会带过去哪些上下文。
- 不要编造不存在的订单、赔付金额或配送状态。
