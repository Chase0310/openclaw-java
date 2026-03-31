# 退款处理示例

## 概览阶段

先输出：
- 批量还是单单处理
- 命中的问题类型
- 建议的处理优先级

概览阶段常见字段：
- `flaggedOrders`
- `facts`
- `suggestions`
- `recommendedNextAction`

## 决策阶段

逐单输出：
- `orderId`
- `issueType`
- `resolutionType`
- `suggestedAmount`
- `agentNote`

补充说明：
- `suggestedAmount`、补偿券金额和是否人工复核，都以工具实时返回的 payload 为准。
- 不要把 reference 中的字段结构示例当成固定业务结果。
