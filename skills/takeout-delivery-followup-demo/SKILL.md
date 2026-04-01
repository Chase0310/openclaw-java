---
name: takeout-delivery-followup-demo
description: 使用 takeout_delivery_followup 工具处理从订单分诊步骤带入的简单配送安抚参数
metadata: |
  {
    openclaw: {
      skillKey: "takeout-delivery-followup-demo"
    }
  }
---

# 外卖配送安抚演示

当上下文里只有一个需要安抚处理的延迟配送订单，或者用户直接询问某一单超时后该怎么安抚时，使用这个 Skill。

参考资料：
- 当需要查看简单承接参数结构时，读取 `references/simple-handoff.md`
- 当需要查看安抚话术示例时，读取 `references/examples.md`

工作流程：
1. 如果上下文里已经有 `orderId`，直接复用，不要重新追问。
2. 调用 `takeout_delivery_followup`，并传入 `action=followup_plan` 与 `orderId`。
3. 优先读取工具 `output` 中可见的稳定字段，不要依赖 Java 内存对象。先看 `status`、`found`、`message`，再看 `record`、`facts`、`suggestions` 与 `recommendedNextAction`。
4. 如果用户继续问“怎么回复用户”“要不要补偿”，继续围绕这一单的配送安抚结果展开，不要扩展成复杂退款场景。
5. 如果上下文里已经出现一批异常订单、赔付策略或用户售后画像，这说明当前场景更适合进入退款处理，而不是配送安抚。
6. 如果 `status=not_found` 或 `found=false`，明确说明没有对应的配送安抚演示数据。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是命中还是未命中。
2. 如果返回中有 `message`，优先用它概括本次安抚结果。
3. 如果返回中有 `record`、`order` 或 `facts`，据此提取事实信息，例如订单号、配送状态、超时情况。
4. 如果返回中有 `suggestions`、`followupPlan`、`recommendedCompensation` 或 `recommendedNextAction`，把它们明确表达为安抚建议、补偿建议和下一步动作。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明没有对应的配送安抚演示数据。
2. 不要编造补偿金额、配送状态或用户回复话术。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成已经生成了安抚方案。

回复规则：
- 用自然中文直接给出安抚话术、解释口径和下一步动作。
- 先描述事实字段，再描述建议字段。
- 这是简单承接场景，重点是快速处理单个订单，不要主动引入批量上下文。
- 明确告诉用户，这些结果来自本地 Java 业务服务的演示数据。
