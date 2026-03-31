---
name: hanxue-learning-plan
description: 使用 hanxue_learning_plan_service 工具消费学习规划 payload，生成定向、全局或温和版的学习路径建议。
user-invocable: false
---

# 学习规划 Skill

当家长问“接下来怎么学”“怎么练”“怎么提升”“学习计划”时，使用这个 Skill。

参考资料：
- `references/knowledge-base.md`
- `references/gentle-mode.md`
- `references/00-总览与通用规则.md`
- `references/01-数学一对一辅学营.md`
- `references/02-语文一对一.md`
- `references/03-英语一对一.md`
- `references/04-高中全科一对一.md`
- `references/05-作业批改.md`
- `references/06-作文批改.md`
- `references/07-计算训练.md`
- `references/08-看图写话.md`
- `references/09-作文辅导.md`
- `references/10-周报.md`
- `references/11-非报告场景出口选项.md`
- `references/12-数学学情日报.md`

约定：
- 相关业务工具遵循当前 runtime 的 payload 规范：成功时读取 `output`，失败时读取 `error`。
- 本 skill 只依赖 `output` 中可见的稳定字段，不依赖 Java 内存对象或仅存在于知识库内部的隐式字段。

工作流程：
1. 优先复用上游 `handoff.params` 或上下文里已有的 `sourceScene`、`currentReportContext`、`knowledgePoint`、`weakPoints`、`childGrade`，不要重复追问。
2. 根据场景选择 action：
   `sourceScene=C` 时调用 `hanxue_learning_plan_service` 并传入 `action=build_gentle_plan`；
   当前有报告上下文时传入 `action=build_targeted_plan`；
   当前没有报告上下文时传入 `action=build_global_plan`。
3. 一并带上当前报告数据、近 7 天学情摘要、年级、来源场景和家长问题，让工具返回统一 payload。
4. 优先读取工具 `output` 中可见的稳定字段。先看 `status`、`found`、`message`，再看 `record`、`records`、`facts`、`suggestions`、`recommendedNextAction` 与 `handoff`。
5. 如果返回里有 `facts.planMode`、`facts.targetSubjects`、`facts.knowledgePoints` 或等价字段，先据此说明“为什么推荐这条路径”。
6. 如果返回里有 `suggestions.recommendedApps`、`suggestions.planPath`、`suggestions.cautions` 或等价字段，按顺序输出 2-3 个应用组成的组合路径，不要只推单个应用。
7. 如果 `status=needs_input`，或 `recommendedNextAction` 明确要求补充学科、知识点、目标，按返回建议追问，不要自己猜。
8. 如果结果里标明温和版场景，只推荐轻量、低压力的路径，不推荐系统上课。
9. 本 skill 一般是终点；除非 payload 明确给出 `handoff`，否则不要继续把学习规划再转给别的 skill。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是正常规划、信息不足、未命中还是需要补充信息。
2. 如果返回中有 `message`，优先用它概括本次规划主线。
3. 如果返回中有 `record`、`records` 或 `facts`，据此提取事实信息，例如来源场景、薄弱点、适用学科、当前模式、限制条件。
4. 如果返回中有 `suggestions`、`recommendedNextAction` 或等价计划字段，把它们明确表达为建议路径，不要说成已经执行完成。
5. 如果返回中同时存在事实字段和建议字段，先说明依据，再说明推荐路径。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明当前没有匹配到更具体的学习规划。
2. 不要编造不存在的应用、年级能力、学习效果或知识库路径。
3. 如果返回里给了保守的 `recommendedNextAction`，只按它提供兜底建议，例如继续下一节课或做轻量复习。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成“孩子只适合这样学”的业务结论。
3. 仅在确有必要时提示稍后再问，或让上游重新提供报告上下文。

回复规则：
- 先说 `facts` / `record` 中的规划依据，再说 `suggestions` / `recommendedNextAction` 中的具体路径。
- 区分“上课”“做题”“订正”“轻量练习”几类动作，不要把应用性质说混。
- 只说应用名和使用顺序，不说家教机里的操作路径。
- 温和版只推荐轻量碎片型应用，每次 5 分钟左右，不给压力，不承诺效果。
- 不推荐知识库里不存在或当前年级不支持的应用；不说“我是 AI”“系统显示”。
