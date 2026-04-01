---
name: hanxue-emotion-support
description: 使用 hanxue_emotion_support_service 工具消费情绪安抚 payload，先共情、再引用行为事实、最后给出温和下一步，并在需要时承接到学习规划或学情解读。
metadata: |
  {
    openclaw: {
      skillKey: "hanxue-emotion-support"
    }
  }
user-invocable: false
---

# 情绪安抚 Skill

当家长表达焦虑、担忧、不满、失望、孩子不想学等负面情绪时，使用这个 Skill。

参考资料：
- `references/emotion-patterns.md`
- `references/11-非报告场景出口选项.md`

约定：
- 相关业务工具遵循当前 runtime 的 payload 规范：成功时读取 `output`，失败时读取 `error`。
- 本 skill 只依赖 `output` 中可见的稳定字段，不依赖 Java 内存对象或未暴露的情绪分类结果。

工作流程：
1. 优先复用当前对话里已经出现的情绪上下文、`recentLearningSummary` 和 `todaySessionSummary`，不要让家长重复描述刚说过的情绪。
2. 调用 `hanxue_emotion_support_service`，传入 `action=support_parent_emotion`，以及家长消息、行为摘要、最近对话和当前场景上下文。
3. 优先读取工具 `output` 中可见的稳定字段。先看 `status`、`found`、`message`，再看 `record`、`facts`、`suggestions`、`recommendedNextAction` 与 `handoff`。
4. 先根据 `facts.emotionType`、`facts.emotionLevel` 或等价字段完成共情，再决定是否引用 `facts.behaviorSignals` 里的学习天数、时长、坚持天数等事实。
5. 如果 `suggestions.shouldTransferToHuman=true`，或者 `recommendedNextAction` 明确要求联系顾问老师，按转人工处理，不要顺带拉回学情。
6. 如果 `handoff.targetSkill=hanxue-learning-plan`，说明家长接受温和建议；直接复用 `handoff.params` 中的 `sourceScene=C`、当前情绪类型和已知上下文，不要重复追问。
7. 如果 `handoff.targetSkill=hanxue-report-interpretation`，说明家长想从情绪场景切回理性看数据；直接复用 `handoff.params` 中的报告上下文或摘要参数。
8. 如果结果里有固定出口选项或等价字段，按产品固定文案输出，不要自由改写。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是正常安抚、未命中、转人工还是需要承接下一步。
2. 如果返回中有 `message`，优先用它概括本次安抚的主结论。
3. 如果返回中有 `record` 或 `facts`，据此提取事实信息，例如情绪类型、行为数据、是否有学习记录、是否存在持续不满信号。
4. 如果返回中有 `suggestions`、`recommendedNextAction` 或 `handoff`，把它们明确表达为温和建议、下一步动作或承接方向，不要说成已经完成的事实。
5. 如果返回中同时有事实字段和建议字段，先共情，再说事实，再说建议。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，说明当前消息里没有足够明确的情绪信号，不要硬套情绪话术。
2. 不要编造学习天数、坚持天数、产品效果或家长情绪强度。
3. 如果返回里带了 `recommendedNextAction`，只按建议的下一步收束，不要擅自延展成长篇方案。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成“孩子没有问题”“家长想多了”之类的情绪结论。
3. 仅在确有必要时提示稍后再聊，或让上游退回到更稳妥的兜底场景。

回复规则：
- 回复顺序固定为：共情 -> 行为事实 -> 温和建议或下一步动作。
- 只把 `facts` / `record` 中的行为数据说成事实，不对数据做未经支持的推测性归因。
- 不直接推荐具体应用；如果要进入“找回兴趣”或“接下来怎么学”，通过 `handoff` 交给 `hanxue-learning-plan`。
- 转人工时不拉回学情，不说“我帮您转接”，只指导家长去联系顾问老师。
- 不说“我是 AI”“系统显示”；但要严格区分事实字段和建议字段。
