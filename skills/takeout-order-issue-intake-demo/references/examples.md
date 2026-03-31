# 订单分诊示例

## `MORNING_OK`

- 全部正常
- 包含 `FOOD-1001`、`FOOD-1008`
- 不跳转

## `LUNCH_TODAY`

- 单笔缺餐
- 命中订单 `FOOD-1002`
- 跳到退款处理

## `DINNER_DELAY`

- 单笔超时
- 命中订单 `FOOD-1004`
- 跳到配送安抚

## `LAST_3_DAYS`

- 多笔异常
- 包含 `FOOD-1002`、`FOOD-1003`、`FOOD-1005`
- 跳到退款处理
