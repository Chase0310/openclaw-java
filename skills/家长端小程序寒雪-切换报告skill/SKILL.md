---
name: hanxue-report-switch
description: 使用 hanxue_report_switch_service 工具消费报告定位 payload，帮助家长找到想看的那份报告，并把上下文承接给学情解读。
metadata: |
  {
    openclaw: {
      skillKey: "hanxue-report-switch"
    }
  }
user-invocable: false
---

# 切换报告 Skill

当家长想看的不是当前正在聊的报告，而是另一份报告，例如“看看昨天的数学报告”“想看报告”“上午那节课怎么样”时，使用这个 Skill。

参考资料：
- `references/fallback-cases.md`
- `references/11-非报告场景出口选项.md`
- `references/12-数学学情日报.md`

约定：
- 相关业务工具遵循当前 runtime 的 payload 规范：成功时读取 `output`，失败时读取 `error`。
- 本 skill 只依赖 `output` 中可见的稳定字段，不依赖 Java 内存对象或隐式列表结构。

工作流程：
1. 优先复用 `extractedParams`、上游 `handoff.params` 或选项条里已经绑定的 `reportId`、日期、学科、过滤条件，不要重复追问。
2. 调用 `hanxue_report_switch_service`，传入 `action=locate_report`，以及家长问题、`todayReportList`、`todayDate`、筛选条件和必要的报告中心上下文。
3. 优先读取工具 `output` 中可见的稳定字段。先看 `status`、`found`、`message`，再看 `record`、`records`、`facts`、`suggestions`、`recommendedNextAction` 与 `handoff`。
4. 如果 `status=ok` 且只命中一份报告，当前 skill 只负责定位确认，不解读报告内容。
5. 如果 `status=needs_input`，或者 `records` 里返回了多份候选报告，按返回的候选列表让家长二选一或多选一，不要替家长猜。
6. 如果 `status=not_found` 且返回里明确是“超出 7 天”或“没有对应学科”，按 `message` 与 `recommendedNextAction` 引导去报告中心，不要硬找。
7. 如果 `handoff.targetSkill=hanxue-report-interpretation`，说明已经定位成功；直接复用 `handoff.params` 里的 `matchedReportId`、`reportType`、`currentReportContext` 等参数，不要再次追问。
8. 如果结果里包含固定候选列表或“没有想看的”等产品选项，只按返回字段原样输出，不要改写句式。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是精确命中、需要补充选择、未命中还是已经可以承接下一步。
2. 如果返回中有 `message`，优先用它概括本次定位结果。
3. 如果返回中有 `record`、`records` 或 `facts`，据此提取事实信息，例如候选报告、匹配到的日期、学科、是否超出可定位范围。
4. 如果返回中有 `recommendedNextAction`，把它明确表达为下一步建议，例如去报告中心继续找。
5. 如果返回中有 `handoff`，先说明为什么已经定位成功，再说明会带过去哪些上下文，并直接复用 `handoff.params`。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明没有找到对应报告。
2. 不要编造不存在的报告、日期、学科或 `reportId`。
3. 正常未命中是业务分支，不要当成系统异常。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成“已经切到了某份报告”。
3. 仅在确有必要时提示稍后重试或让上游重新提供筛选参数。

回复规则：
- 当前 skill 只做定位，不做学情解读，不做错因分析，不给学习建议。
- 先描述 `facts` / `record` 中的定位事实，再描述 `recommendedNextAction` 或 `handoff` 中的下一步。
- 命中一份报告时，回复只需要确认“找到了”；具体摘要和选项条由 `hanxue-report-interpretation` 接管。
- 多份候选时只列候选，不替家长拍板。
- 不说“我是 AI”“系统显示”；但要严格区分事实字段和建议字段。
