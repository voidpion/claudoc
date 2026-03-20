# Agent 上下文工程优化建议

结合当前知识库场景，以下是几个方向的建议：

## 1. System Prompt 结构化

当前的 system prompt 比较笼统。可以分层组织：

```
[身份与行为准则]     — 你是谁、怎么做
[可用工具说明]       — 什么时候该用哪个工具（而不是让模型自己猜）
[知识库当前状态摘要] — 动态注入，比如文档数量、目录结构概览
[当前会话上下文]     — L2/L1 摘要
```

具体建议：
- **加入工具选择指引**：比如 "当用户问'有哪些文档'时用 `list_notes`；当用户问某个主题时先用 `retrieve` 再用 `read_note` 获取全文"。现在模型全靠自己判断，效果不稳定
- **动态注入知识库概览**：每次对话开始时把目录树摘要放进 system prompt，这样简单问题不需要调工具

## 2. Tool Description 优化

当前工具描述偏简单，模型容易用错。关键改进：

- **加 when to use / when not to use**：`retrieve` 和 `search` 容易混淆，应明确说 "retrieve 用于语义相似性查询，search 用于精确关键词匹配"
- **加参数示例**：比如 `create_note` 的 path 参数，给出 `"例如 /notes/meeting/2024-01-01.md"` 而不只是 `"File path"`
- **加返回值说明**：模型不知道工具会返回什么格式，导致它不确定是否需要进一步调用

## 3. 多步推理引导（Tool Chaining）

当前 agent 经常一步到位或者用错工具。可以在 system prompt 里加入典型工作流：

```
常见操作模式：
- 回答知识库问题：retrieve → read_note(获取全文) → 回答并引用来源
- 创建新笔记：先 search 检查是否已存在 → create_note
- 修改笔记：先 read_note 获取当前内容 → update_note
```

这比让模型自己摸索高效得多。

## 4. Context Window 利用率

当前的分层压缩机制是对的，但可以优化：

- **L0 保留策略**：现在是按 token 数切半压缩。更好的做法是保留最近的 user-assistant 完整轮次（不要切到一半），以及所有未完成的 tool 调用链
- **工具结果压缩**：`list_notes` 和 `retrieve` 的结果可能很长，但在后续轮次中价值递减。可以在保存时就截断工具结果（比如只保留前 500 字符），或者在 L0→L1 压缩时特别处理工具结果
- **区分"事实性"和"过程性"内容**：压缩时，用户说的偏好/决策（事实性）比工具调用的中间过程更值得保留

## 5. Retrieval 质量提升

当前 mock TF-IDF 的检索质量有限：

- **query 改写**：在调用 `retrieve` 前，让模型先把用户问题改写成更适合检索的形式。比如用户问 "RAG 怎么工作的"，改写为 "RAG retrieval augmented generation 原理 流程"
- **retrieve 结果后处理**：返回 chunk 时附带文档标题和路径，方便模型判断相关性并决定是否需要 `read_note` 获取全文
- **多路召回**：同时用 `retrieve`（语义）和 `search`（关键词），合并去重，这个可以做成一个组合工具 `smart_search`

## 6. 实操优先级

如果只改一个地方，**优化 system prompt 加入工具选择指引** 投入产出比最高。

---

# 记忆系统设计

## 一、会话内记忆（分层压缩）

在 `MemoryManager.java` 中实现，三层结构：

| 层级 | 存储内容 | 触发条件 | 机制 |
|------|---------|---------|------|
| **L0** | 原始对话消息 | 始终保留最近的 | 直接存 DB |
| **L1** | 近期摘要 | L0 token > 4000 | 取最老一半 L0，调 LLM 生成摘要 |
| **L2** | 全局摘要 | L1 数量 > 5 | 合并所有 L1 + 旧 L2，调 LLM 生成新摘要 |

上下文组装顺序（`buildContext`）：
```
system_prompt → L2全局摘要 → L1近期摘要列表 → L0原始消息 → 当前用户消息
```

压缩后旧消息标记 `archived=true`，不再进入上下文但保留在 DB 中。

配置在 `application.yml`：
```yaml
agent:
  memory:
    l0-max-tokens: 4000
    l1-max-count: 5
```

## 二、跨会话长期记忆

没有单独的记忆存储，而是复用知识库本身：

- Agent 通过 `create_note` / `update_note` 工具，在 `/_memory/` 路径下写入记忆文档
- 这些文档和普通笔记一样被 chunking + embedding
- 后续会话中 Agent 通过 `retrieve` 自然检索到历史记忆
- System prompt 里引导 Agent 主动记忆重要信息

初始化时预置了一篇 `/_memory/system-init.md` 作为示例。

## 当前的局限

1. **L0→L1 压缩粒度粗** — 按 token 数切半，可能切断完整的工具调用链
2. **工具结果不压缩** — `list_notes` 返回的大 JSON 原样保留，浪费 token
3. **assistant 消息的 tool_calls 之前没持久化** — 刚修复了这个 bug
4. **长期记忆是被动的** — 完全依赖模型自己判断何时写 `/_memory/`，实际触发率很低
