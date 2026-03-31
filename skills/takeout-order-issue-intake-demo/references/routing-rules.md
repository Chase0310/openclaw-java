# 订单分诊分流规则

## 跳到退款处理

当异常订单里包含以下问题类型之一时，优先跳到 `takeout-refund-resolution-demo`：
- `missing_item`
- `spilled_drink`
- `wrong_item`

复杂承接参数示例：

```json
{
  "customerId": "USER-3001",
  "timeRangeKey": "LAST_3_DAYS",
  "windowSummary": {
    "timeRangeKey": "LAST_3_DAYS",
    "timeRangeLabel": "最近三天",
    "customerId": "USER-3001",
    "totalOrders": 5,
    "problemOrders": 3
  },
  "flaggedOrders": [
    {
      "orderId": "FOOD-1002",
      "issueType": "missing_item",
      "deliveryStatus": "delivered",
      "amount": 29.0,
      "suggestedAction": "partial_refund"
    }
  ],
  "orderIds": ["FOOD-1002"],
  "resolutionPolicyContext": {
    "maxAutoRefundPerOrder": 12.0,
    "allowCouponForLateDelivery": true
  },
  "customerAfterSalesProfile": {
    "customerId": "USER-3001",
    "recentAfterSalesCount": 2,
    "hasRepeatedIssues": false
  }
}
```

## 跳到配送安抚

当只有 1 单异常，且问题类型是 `late_delivery` 时，跳到 `takeout-delivery-followup-demo`。

简单承接参数示例：

```json
{
  "orderId": "FOOD-1004"
}
```
