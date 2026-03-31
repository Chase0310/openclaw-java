# 配送安抚示例

输出重点：
- 先道歉
- 解释配送拥堵或晚点原因
- 明确下一步动作
- 说明补偿方式

返回中可能出现的事实字段：
- `orderId`
- `deliveryStatus`
- `delayMinutes`
- `latestEta`

返回中可能出现的建议字段：
- `customerReply`
- `explanation`
- `recommendedCompensation`
- `recommendedNextAction`

典型话术方向：
- “抱歉这单配送晚了一些”
- “我们已记录本次超时并跟进骑手状态”
- “会补发一张配送补偿券”

注意：
- 具体延迟时长、最新 ETA 和补偿方式都以工具返回为准。
