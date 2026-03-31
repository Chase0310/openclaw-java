# 订单分诊分流规则

## 跳到退款处理

当异常订单里包含以下问题类型之一时，优先跳到 `takeout-refund-resolution-demo`：
- `missing_item`
- `spilled_drink`
- `wrong_item`

复杂承接参数示例：

```json
{
  "customerId": "<customerId>",
  "timeRangeKey": "<timeRangeKey>",
  "windowSummary": {
    "timeRangeKey": "<timeRangeKey>",
    "timeRangeLabel": "<timeRangeLabel>",
    "customerId": "<customerId>",
    "totalOrders": 0,
    "problemOrders": 0
  },
  "flaggedOrders": [
    {
      "orderId": "<orderId>",
      "issueType": "missing_item",
      "deliveryStatus": "delivered",
      "amount": 0.0,
      "suggestedAction": "partial_refund"
    }
  ],
  "orderIds": ["<orderId>"],
  "resolutionPolicyContext": {
    "maxAutoRefundPerOrder": 0.0,
    "allowCouponForLateDelivery": true
  },
  "customerAfterSalesProfile": {
    "customerId": "<customerId>",
    "recentAfterSalesCount": 0,
    "hasRepeatedIssues": false
  }
}
```

## 跳到配送安抚

当只有 1 单异常，且问题类型是 `late_delivery` 时，跳到 `takeout-delivery-followup-demo`。

简单承接参数示例：

```json
{
  "orderId": "<orderId>"
}
```
