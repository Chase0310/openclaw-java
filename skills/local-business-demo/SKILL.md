---
name: local-business-demo
description: 通过 local_business_lookup 工具处理本地客户与订单问题
metadata: |
  {
    openclaw: {
      skillKey: "local-business-demo"
    }
  }
---

# 本地业务演示

当用户询问本地业务记录，例如客户信息、订单状态、付款情况、履约进展时，使用这个 Skill。

可用示例 ID：
- `CUST-1001`
- `CUST-1002`
- `ORD-9001`
- `ORD-9002`

工作流程：
1. 如果用户询问客户档案，调用 `local_business_lookup`，并传入 `action=customer_profile`。
2. 如果用户只想看订单当前状态，调用 `local_business_lookup`，并传入 `action=order_status`。
3. 如果用户希望得到同时包含订单和客户上下文的综合说明，调用 `local_business_lookup`，并传入 `action=order_overview`。
4. 如果用户没有提供可用的业务 ID，先追问，不要猜测。
5. 如果工具返回未找到记录，绝不编造付款状态、发货状态或客户等级。

回复规则：
- 用自然中文概括工具结果。
- 如果结果里包含 `recommendedNextAction`，把它明确说明为“下一步建议”。
- 明确告诉用户，这些结果来自本地 Java 业务服务。
