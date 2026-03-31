---
name: hanxue-feature-guide
description: 使用 hanxue_feature_guide_service 工具消费功能知识库 payload，回答小程序功能怎么用，并返回固定跳转入口。
user-invocable: false
---

# 功能引导 Skill

当家长问小程序里的某个功能怎么用，例如管控密码、答案管控、应用管控、使用时间、护眼、屏幕截图、学情报告入口等问题时，使用这个 Skill。

参考资料：
- `references/page-mapping.md`
- `references/special-cases.md`
- `references/11-非报告场景出口选项.md`

约定：
- 相关业务工具遵循当前 runtime 的 payload 规范：成功时读取 `output`，失败时读取 `error`。
- 本 skill 只依赖 `output` 中可见的稳定字段，不依赖 Java 内存对象或未暴露的映射表结构。

工作流程：
1. 优先复用路由或上游 `handoff.params` 里已经识别出的 `featureKey`、`pageKey`、`jumpEntry`，不要重复追问。
2. 调用 `hanxue_feature_guide_service`，传入 `action=lookup_feature_guide`，以及家长问题、功能关键词和必要的小程序上下文。
3. 优先读取工具 `output` 中可见的稳定字段。先看 `status`、`found`、`message`，再看 `record`、`facts`、`suggestions`、`recommendedNextAction` 与 `handoff`。
4. 如果返回中有 `facts.featureName`、`facts.featurePurpose`、`facts.pageLocation` 或等价字段，先用 1-2 句解释这个功能是什么、在哪。
5. 如果返回中有 `suggestions.jumpEntry`、`suggestions.fixedCopy` 或等价字段，必须使用它给出的固定跳转文案，不要改写。
6. 如果返回中标明“小程序暂不支持”或“需在家教机端操作”，只做文字说明，不附跳转入口。
7. 如果 `suggestions.shouldTransferToHuman=true`，或者 `recommendedNextAction` 明确要求联系顾问老师，按转人工处理，不要再给跳转入口。
8. 本 skill 一般不需要 handoff；如果 payload 显式给出 `handoff`，只复用 `handoff.params`，不要重复追问已有功能信息。

返回结果解读规则：
1. 先看 `status` 与 `found`，判断是正常命中、小程序不支持、需要转人工还是未命中。
2. 如果返回中有 `message`，优先用它概括本次功能说明的主结论。
3. 如果返回中有 `record` 或 `facts`，据此提取事实信息，例如功能用途、页面位置、是否默认开启、是否需要重置。
4. 如果返回中有 `suggestions` 或 `recommendedNextAction`，把它们明确表达为跳转入口、处理建议或下一步动作，不要说成已经完成的事实。

未命中规则：
1. 如果 `status=not_found` 或 `found=false`，明确说明当前没有匹配到这个小程序功能说明。
2. 不要编造默认密码、隐藏功能、页面入口或跳转文案。
3. 如果返回里明确是“小程序没有这个功能”，把它当作正常业务分支，不要当成系统错误。

错误规则：
1. 如果工具执行失败，把返回错误视为参数问题或系统异常。
2. 不要把错误包装成“已经给到正确入口”或“功能一定存在”。
3. 仅在确有必要时提示稍后再试，或让上游改走兜底或转人工场景。

回复规则：
- 回复顺序固定为：功能说明 -> 所在位置或特殊说明 -> 固定跳转入口。
- 只把 `facts` / `record` 中的信息说成事实；把 `jumpEntry`、`recommendedNextAction` 等明确说成操作建议。
- 跳转入口文案必须原样使用，不要改措辞。
- 不帮家长直接操作，不透露密码数值，不解释家教机端详细操作路径。
- 不说“我是 AI”“系统显示”；但要严格区分事实字段和建议字段。
