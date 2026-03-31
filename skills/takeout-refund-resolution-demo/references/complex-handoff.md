# 复杂承接参数

退款处理默认承接的是订单分诊阶段整理好的完整上下文。

关键字段：
- `customerId`
- `timeRangeKey`
- `windowSummary`
- `flaggedOrders`
- `orderIds`
- `resolutionPolicyContext`
- `customerAfterSalesProfile`

说明：
- `flaggedOrders` 是事实与建议的混合视图
- `resolutionPolicyContext` 是策略上下文
- `customerAfterSalesProfile` 是客户历史售后画像

如果只有简单的 `orderId`，不要假装已经拿到了这套复杂参数。
