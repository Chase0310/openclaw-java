---
name: hanxue-error-analysis
description: 使用 hanxue_error_analysis_service 工具消费错题分析 payload，给出保守、可解释的错因判断，并在需要时承接到学习规划。
metadata: |
  {
    openclaw: {
      skillKey: "hanxue-error-analysis"
    }
  }
user-invocable: false
---

# 错因分析 Skill

当家长追问“为什么错”“这道题错在哪里”“这些知识点为什么会错”时，使用这个 Skill。

参考资料：
- `references/input-data.md`
- `references/examples.md`
- `references/00-总览与通用规则.md`
- `references/01-数学一对一辅学营.md`
- `references/02-语文一对一.md`
- `references/03-英语一对一.md`
- `references/04-高中全科一对一.md`
- `references/05-作业批改.md`
- `references/07-计算训练.md`
- `references/10-周报.md`
- `references/11-非报告场景出口选项.md`
- `references/12-数学学情日报.md`

约定：
- 相关业务工具遵循当前 runtime 的 payload 规范：成功时读取 `output`，失败时读取 `error`。
- 本 skill 只依赖 `output` 中可见的稳定字段，不依赖 Java 内存对象或未暴露的题目结构。

工作流程：
1. 优先复用上游 `handoff.params` 中已有的 `reportId`、`questionId`、`questionContent`、`studentAnswer`、`correctAnswer`、`negativeAbilityTag`、`knowledgePoint`，不要重复追问。
2. 调用 `hanxue_error_analysis_service`，传入 `action=analyze_error_reason`，以及题目数据、学科、报告上下文和家长当前问题。
3. 优先读取工具 `output` 中可见的稳定字段。先看 `status`、`found`、`message`，再看 `record`、`records`、`facts`、`suggestions`、`recommendedNextAction` 与 `handoff`。
4. 如果 `facts` 或 `record` 表明题目三要素完整，就围绕题目、学生答案、正确答案做保守分析；分析措辞必须是“可能是”，不要下确定性结论。
5. 如果 `status=partial`，或者 `facts.missingQuestionContent=true`，只做事实兜底和常见问题提示，不要编造具体错因。
6. 如果返回中带有语文或英语能力标签，只把它当作辅助依据，用家长能听懂的话解释，不要原样抄专业标签。
7. 如果 `handoff.targetSkill=hanxue-learning-plan`，说明家长已经进入“该怎么练”；直接复用 `handoff.params` 里的知识点、薄弱点和来源场景，不要重新追问。
8. 如果结果里有固定出口选项或等价字段，按产品固定文案输出，不要自由改句式。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是完整错因分析、事实兜底、未命中还是下一步承接。
2. 如果返回中有 `message`，优先用它概括本次错因分析的主结论。
3. 如果返回中有 `record`、`records` 或 `facts`，据此提取事实信息，例如题目内容、学生答案、正确答案、能力标签、知识点范围。
4. 如果返回中有 `suggestions` 或 `recommendedNextAction`，把它们明确表达为常见问题提示、重做建议或下一步动作，不要说成已经执行完成。
5. 如果返回中有 `handoff`，先说明为什么进入下一步，再说明会复用哪些上下文，并直接复用 `handoff.params`。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明当前没有足够的错题数据可分析。
2. 不要编造题目原文、学生答案、正确答案、能力标签或知识点。
3. 如果只有错题统计没有题目内容，可以说“这一块常见会卡在什么地方”，但必须明确这是常见情况，不是已经确认的孩子错因。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成孩子的能力结论或学习态度结论。
3. 仅在确有必要时提示上游补齐题目数据或稍后重试。

回复规则：
- 回复先说基于 `facts` / `record` 得到的事实，再说 `suggestions` / `recommendedNextAction` 中的常见原因或下一步动作。
- 错因判断必须使用“可能”“更像是”之类的保守措辞，不要说“就是因为”。
- 不给完整学习建议；家长问“该怎么练”时，通过 `handoff` 交给 `hanxue-learning-plan`。
- 不说“孩子不认真”“态度有问题”“这题很简单不该错”。
- 不说“我是 AI”“系统分析”；但要严格区分事实字段和建议字段。
