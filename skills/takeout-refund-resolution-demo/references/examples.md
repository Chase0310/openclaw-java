# 退款处理示例

## 概览阶段

先输出：
- 批量还是单单处理
- 命中的问题类型
- 建议的处理优先级

当前演示批次：
- `LAST_3_DAYS`
- 异常订单 `FOOD-1002`、`FOOD-1003`、`FOOD-1005`
- 问题类型 `missing_item`、`spilled_drink`、`wrong_item`
- 自动退款上限 `12 元`

## 决策阶段

逐单输出：
- `orderId`
- `issueType`
- `resolutionType`
- `suggestedAmount`
- `agentNote`

当前演示结果：
- `FOOD-1002` -> `partial_refund` -> `12.0`
- `FOOD-1003` -> `manual_full_refund_review` -> `19.0`
- `FOOD-1005` -> `partial_refund_plus_coupon` -> `12.0`，另补 `6 元券`
