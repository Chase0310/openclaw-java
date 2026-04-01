---
name: hanxue-push-polish
description: 使用 hanxue_push_polish_service 工具消费推送摘要 payload，把结构化事实翻译成家长可读的推送消息。
metadata: |
  {
    openclaw: {
      skillKey: "hanxue-push-polish"
    }
  }
user-invocable: false
---

# 推送润色 Skill

当学情报告已经生成，需要把结构化事实数据翻译成一条口语化推送消息时，使用这个 Skill。

参考资料：
- `references/report-branches.md`
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
- `references/12-数学学情日报.md`

约定：
- 相关业务工具遵循当前 runtime 的 payload 规范：成功时读取 `output`，失败时读取 `error`。
- 本 skill 只依赖 `output` 中可见的稳定字段，不依赖 Java 内存对象或未暴露的原始 JSON 结构。

工作流程：
1. 调用 `hanxue_push_polish_service`，传入 `action=render_push_text`，以及 `reportType`、事实数据、孩子姓名和必要的推送上下文。
2. 优先读取工具 `output` 中可见的稳定字段。先看 `status`、`found`、`message`，再看 `record`、`facts`、`suggestions` 与 `recommendedNextAction`。
3. 如果返回中有 `facts.reportType`、`facts.keyNumbers`、`facts.keyFindings` 或等价字段，先确定这一类报告“该说什么、不该说什么”的边界。
4. 推送只说事实和简单结论，不提前把错因分析、学习建议、详细分层讲完。
5. 如果返回中有术语替换字段，例如把 `PT` 转成“题型”，把“未掌握”转成“还需要多练练”，按返回口径做人话转换。
6. 如果当前报告要求更短字数，例如看图写话、作文辅导、计算训练，严格按返回中的长度约束压缩，不要加解释。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是正常生成推送、事实不足还是未命中。
2. 如果返回中有 `message`，优先把它作为推送主句或主结论。
3. 如果返回中有 `record` 或 `facts`，据此提取事实信息，例如时长、做题量、通关数、得分、亮点、需要关注的数量。
4. 如果返回中有 `suggestions`、`tone`、`wordLimit` 或等价字段，把它们当作润色约束，不要说成业务事实。
5. 推送场景一般不需要 handoff；如果有 `recommendedNextAction`，只把它理解为程序侧后续动作，不写进家长可见文案。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明当前没有足够事实生成推送。
2. 不要编造时长、题数、掌握情况、评分或进步结论。
3. 未命中属于业务分支，应交给上游决定是否跳过发送，不要自行拼接模糊话术。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成已经生成了可信推送。
3. 不要在异常时输出带有具体数值的假消息。

回复规则：
- 只输出家长可见的推送文案本体，不输出选项条、不输出解释过程。
- 先用 `facts` / `record` 中的事实生成主句，再按 `suggestions` 中的语气和长度约束润色。
- 不修改数值，不加入事实数据里没有的信息，不承诺效果。
- 不说“系统显示”“根据数据”“我是 AI”，也不直接抛专业术语给家长。
- 推送是对话起点，不把下一层错因和学习建议提前说完。
