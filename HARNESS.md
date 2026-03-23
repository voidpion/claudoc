# Harness Engineering 规划

基于 Claudoc 项目现状的 Harness Engineering 分析与建议。

---

## 目录

- [1. 并发安全（当前有 Bug）](#1-并发安全当前有-bug)
- [2. 可观测性](#2-可观测性)
- [3. 测试与评估](#3-测试与评估)
- [4. 韧性与容错](#4-韧性与容错)
- [5. 安全护栏](#5-安全护栏)
- [6. 成本控制](#6-成本控制)
- [7. 记忆压缩的可靠性](#7-记忆压缩的可靠性)
- [8. 优先级排序](#8-优先级排序)
- [9. 记忆维护方案](#9-记忆维护方案)
- [10. 上下文组装策略](#10-上下文组装策略)

---

## 1. 并发安全 ✅ 已修复

`AnthropicClient` 和 `OpenAiCompatibleClient` 都是 Spring 单例，但把 `pendingToolCalls`、`currentBlockIndex` 等**流式解析状态存为实例字段**。多个用户同时聊天会互相踩数据。

**修复方案：** 将解析状态封装为 `StreamState` 内部类，每次 `chatStream()` 调用创建独立实例，通过参数传入 `processSSELine()`，消除共享可变状态。

---

## 2. 可观测性

当前只有散落的 `log.info/error`，没有结构化指标。建议分三层建设：

| 层级 | 要追踪什么 | 技术选型 |
|------|-----------|---------|
| **Metrics** | 每轮 token 消耗、LLM 延迟(P50/P99)、工具调用频率/失败率、压缩触发频率 | Micrometer + Prometheus |
| **Tracing** | 一次用户请求 → N 轮 agent loop → 每轮的 LLM 调用 + 工具执行串成完整链路 | OpenTelemetry，每个 loop iteration 一个 span |
| **Logging** | 结构化日志，关键字段（conversationId, iteration, toolName, tokenCount）可检索 | JSON format + ELK 或 Loki |

最关键的指标是 **每次对话的总 token 消耗** 和 **工具调用成功率**——这两个直接影响成本和用户体验。

---

## 3. 测试与评估

当前项目零测试。这是 harness engineering 的核心，建议分三级建设：

### A. 工具单元测试

- 每个 `AgentTool.execute()` 独立测试，覆盖正常路径和异常路径
- Mock `KnowledgeBaseService`，验证参数校验和错误处理

### B. Agent Loop 集成测试

- Mock `LlmClient` 返回预设的 tool_call 序列，验证多轮循环的正确性
- 测试边界情况：10 次迭代上限、空响应、解析失败等

### C. Eval Harness（端到端评估）

- 准备一组 test cases：`{用户问题, 期望的工具调用序列, 期望的回答要点}`
- 跑真实 LLM，用打分函数评估回答质量
- 关键场景：
  - 单步工具调用
  - 多步组合调用（retrieve → read_note → 回答）
  - 歧义消解（依赖 UI 上下文）
  - 记忆压缩后上下文保留度

---

## 4. 韧性与容错（部分已修复 ✅）

| 问题 | 现状 | 建议 | 状态 |
|------|------|------|------|
| LLM API 瞬时失败 | 直接报错给用户 | 指数退避重试 2-3 次 | 待做 |
| LLM API 持续不可用 | 线程挂起直到 SSE 300s 超时 | Circuit Breaker（Resilience4j），快速失败 | 待做 |
| 单个工具执行时间过长 | 无限等待 | 给工具执行加超时（如 30s） | ✅ `ToolRegistry` 通过 `Future.get(timeout)` 实现，超时可配置 |
| `CachedThreadPool` 无上限 | 高并发时 OOM | 改为有界线程池 + 拒绝策略 | ✅ `AgentLoop` 改为 `ThreadPoolExecutor(core=2, max=5)`，`CallerRunsPolicy` 背压 |

---

## 5. 安全护栏

| 方面 | 当前状态 | 建议 |
|------|---------|------|
| 输入校验 | 工具参数 JSON 解析失败默默变空 Map | 严格校验，返回明确错误给 LLM |
| Prompt 注入 | 用户输入直接拼入上下文 | 对用户消息做 sanitization，在 system prompt 中强化 instruction hierarchy |
| 输出过滤 | 无 | 关键场景过滤 LLM 输出中的敏感信息（如果知识库含敏感数据） |
| 工具权限 | 所有工具对所有用户开放 | 按角色/场景限制可用工具集 |
| API 认证 | 无 | 至少加 API key 或 JWT |
| 密钥管理 | API key 硬编码在 application.yml | 环境变量或 Vault |

---

## 6. 成本控制

当前唯一的限制是 `MAX_TOOL_ITERATIONS = 10`，但这意味着一次用户消息最多触发 **10 次 LLM 调用 + 1 次压缩调用 = 11 次 API 调用**，没有任何 token 预算机制。

建议：

- **Per-request token budget**：累计 input+output tokens，超过阈值（如 50K tokens）强制终止
- **Usage tracking**：记录每次 LLM 调用的 token 消耗到数据库，支持统计和告警
- **并发请求限制**：每用户同时只允许一个 agent loop 运行

---

## 7. 记忆压缩的可靠性 ✅ 已修复

原有风险：

- 压缩 LLM 调用失败 → L0 消息持续膨胀 → 上下文超出模型窗口 → 后续请求全部报错
- `estimateTokens()` 用 `字符数/4` 估算，中文场景偏差较大（中文约 1-2 字符/token）

已完成的修复：

### 7.1 CJK 感知的 Token 估算

LLM 的 tokenizer 对不同语言的切分粒度不同：英文约 4 字符/token（`hello` ≈ 1 token），但中文每个字接近 1 个 token（`你好` ≈ 2 tokens）。原来统一用 `字符数/4` 会导致中文内容的 token 数被严重低估（实际可能是估算值的 2-3 倍），使得压缩触发过晚、上下文溢出。

CJK（Chinese, Japanese, Korean）感知方案：遍历文本字符，识别 CJK 字符按 1.5 字符/token 计算，其余按 4 字符/token 计算，混合文本场景下估算精度显著提升。

### 7.2 压缩重试 + 降级

`callLlmForSummary()` 增加最多 2 次重试。全部失败后降级为机械截断（保留前 500 字符），确保旧消息始终被归档，不会因为压缩失败而无限膨胀。

### 7.3 上下文硬上限

`buildContext()` 在加载 L0 消息前，先计算 system prompt 各层已占用的 token 数和 LLM 输出预留空间，剩余 token 预算从最新的 L0 消息往回填充。超出预算的旧消息被丢弃，保证总上下文不超过模型窗口（`llm.context-window` 可配置，默认 8000）。

---

## 8. 优先级排序

按投入产出比排序：

| 优先级 | 事项 | 说明 | 状态 |
|--------|------|------|------|
| **P0** | 修复并发 Bug | 紧急，会导致线上错误 | ✅ 已修复 |
| **P1** | 加基础 Metrics | token 消耗 + LLM 延迟 + 工具成功率 | 待做 |
| **P2** | 工具执行超时 + 有界线程池 | 防止资源耗尽 | ✅ 已修复 |
| **P3** | Eval Harness | 持续衡量 Agent 质量的基础设施 | 待做 |
| **P4** | LLM 调用重试 + 熔断 | 提升可用性 | 待做 |
| **P5** | 成本控制（token budget） | 防止意外的高额账单 | 待做 |
| **P6** | 安全护栏（输入校验 + prompt 注入防护） | 安全基线 | 待做 |
| **P7** | 密钥管理 + API 认证 | 生产化必须 | 待做 |
| **额外** | 记忆压缩可靠性 | CJK 感知 token 估算 + 压缩重试降级 + 上下文硬上限 | ✅ 已修复 |
| **额外** | 日志规范化 | logback-spring.xml，主日志 + Agent 专用日志分离 | ✅ 已修复 |
| **额外** | UI 上下文空状态 | UiActionTracker 空时注入"无操作"提示，防止 LLM 瞎猜 | ✅ 已修复 |

P0-P2 是基本的工程质量保障，P3 是 Agent 系统特有的评估基础设施，P4-P7 是生产化必须的。

---

## 9. 记忆维护方案

### 问题

当前 Agent 收到任何用户信息都会主动调用 `create_note` 在 `/_memory/` 下创建新文档，导致知识库中零散记忆文件不断膨胀。根因是系统提示词中的 `proactively save` 指令触发条件过于宽泛。

### 方案：分类收敛到 3 个固定文件

所有长期记忆只写入以下 3 个文件，不创建其他 `/_memory/` 文档：

| 文件 | 定位 | 内容示例 |
|------|------|----------|
| `/_memory/user.md` | 用户画像 — "你是谁" | 名字、角色、偏好、习惯 |
| `/_memory/decisions.md` | 项目决策 — "怎么做" | 技术选型、流程约定、规则设定 |
| `/_memory/facts.md` | 外部事实 — "世界是什么样" | IP、邮箱、日程、第三方信息 |

### 分类判断

```
用户说了什么？
  ├─ 关于自己（名字、角色、喜好、习惯）→ user.md
  ├─ 关于项目怎么做（技术选型、约定、规则）→ decisions.md
  ├─ 关于外部世界的事实（IP、账号、日期、第三方信息）→ facts.md
  └─ 闲聊 / 临时信息 → 不记录
```

### 文件格式

每个文件统一用 Markdown 列表，每条带时间戳，便于判断新旧和去重：

```markdown
# User Profile

- [2026-03-22] 名字：张瑾
- [2026-03-22] 偏好：简洁回答，不要 emoji
- [2026-03-23] 角色：后端开发，熟悉 Spring Boot
```

### 写入规则

1. **不主动创建** — 只有用户显式要求记住，或信息明确属于长期有效的画像/决策时才写入
2. **先读后写** — 写入前必须 `read_note` 目标文件，了解已有内容
3. **追加或更新** — 同类信息已存在则更新（替换旧条目），否则追加新条目
4. **不新建文件** — 所有记忆只写入这 3 个文件，不创建额外 `/_memory/` 文档
5. **闲聊不记** — 起名字、打招呼、随口一说的内容不触发写入

### System Prompt 改动

删除原有的 `proactively save` 指令，替换为：

```
## Memory Management

You have three memory files. ONLY write to these when the user explicitly asks
you to remember something, or when the information is clearly long-term valuable:

- /_memory/user.md — Who the user is (name, role, preferences, habits)
- /_memory/decisions.md — Project decisions (tech choices, conventions, rules)
- /_memory/facts.md — External facts (IPs, emails, schedules, third-party info)

Rules:
- ALWAYS read_note the target file before writing, to check existing content.
- UPDATE existing entries instead of duplicating. Append only if it's new info.
- NEVER create other files under /_memory/. Only these three files exist.
- DO NOT save casual chat, greetings, or ephemeral information.
- Each entry must include a date tag: [YYYY-MM-DD]
```

### 初始化

3 个文件初始不存在，Agent 在第一次需要写入时才创建对应文件，最多创建 3 个。

### 与其他工具的协作

记忆写入可以和其他 tool_call 在同一轮中并行执行。记忆更新依赖先读后写，由 LLM 在多轮 agent loop 中自行编排，不需要后端做特殊处理。

---

## 10. 上下文组装策略

### 设计决策：独立 messages 数组 vs 合并为大文本

当前采用 LLM API 原生的 `messages[]` 数组格式，每条消息保持独立的 role 标记。**不将对话历史合并为一个大的 JSON 文本塞进 system prompt。**

选择 `messages[]` 的原因：

#### 1. 角色语义

LLM API 按 role 区分说话人，模型内部对不同 role 的处理权重不同。`user`、`assistant`、`tool` 三种角色有明确的语义边界。合并成一个大文本串，模型无法区分谁说了什么。

#### 2. Tool Call 配对

工具调用依赖结构化的 `tool_call_id` 引用链：

```
assistant → tool_calls: [{id: "tc_1", name: "search", ...}]
tool      → tool_call_id: "tc_1", content: "结果..."
```

这个配对关系是 API 协议层的，合并成文本会丢失。

#### 3. Role 优先级（Instruction Hierarchy）

模型在训练时学到了隐含的优先级层次：

```
system（开发者指令）> user（用户输入）> assistant（自己之前说的）
```

安全规则、身份设定等指令放在 `system` role 时，模型更倾向于坚守规则。如果只是拼成文本混在 user 消息里，同一优先级的后续 user 消息更容易覆盖它，削弱 prompt injection 防护。

### Role Marker 开销

每条消息有约 3-4 tokens 的固定开销（角色标记、消息分隔符等）。当前 4-6 条 system 消息约 16-24 tokens 开销，对 8000 token 的上下文窗口来说可以忽略。

如需优化，可将多条 system 消息合并为 1-2 条（静态部分合并、动态部分合并），但 L0 的 user/assistant/tool 消息必须保持独立的 messages 结构。

### 当前上下文分层结构

```
messages[0]   system    身份 + 规则 + 记忆管理（IDENTITY_AND_RULES）
messages[1]   system    工具选择指南 + 工作流（TOOL_GUIDANCE）
messages[2]   system    [Knowledge Base Overview] — 动态注入的 KB 概览
messages[3]   system    [Recent User Actions] — UI 上下文
messages[4]   system    [Global Context Summary] — L2 全局摘要（如果有）
messages[5]   system    [Recent Summary] — L1 近期摘要（如果有，可多条）
messages[6+]  user/assistant/tool  L0 原始对话消息（在 token 预算内从新往旧填充）
```

### 历史消息管理最佳实践

#### 1. 永远不要断开配对

Tool call 和 tool result 必须成对出现。裁剪时要按完整的对话轮（user + assistant + tool×N）为单位，不能切断一个轮次中间。当前 `findRoundBoundary()` 按 user 消息边界切分，保证不会切断完整的对话轮。

#### 2. 先压缩，再裁剪

优先级：**摘要 > 截断 > 丢弃**。旧消息优先通过 LLM 摘要保留关键事实；摘要失败时机械截断（保留前 N 字符）；都失败了才丢弃。当前的三级压缩（L0→L1→L2）遵循这个思路。

#### 3. Tool Result 截断

工具返回值通常很长，但对后续轮次的价值递减。当前轮保留完整 tool result，历史轮压缩时截断到 ~500 字符，摘要中只记"调了什么工具、关键结果是什么"。

#### 4. 保留最近、压缩最远

Token 预算优先给最近的消息。从最新的 L0 消息往回填充，超出预算的旧消息已被 L1/L2 摘要覆盖，可以丢弃。

#### 5. System Prompt 静态/动态分离

静态部分（身份、规则、工具指南）每次相同，可缓存 token 计数。动态部分（KB 概览、UI 上下文）每次重新生成。部分 LLM API 支持 prefix caching，相同前缀不重复计费——把不变内容放前面，变化内容放后面，能节省成本。

#### 6. 消息角色交替规则

大部分 LLM API 要求 user/assistant 交替出现（tool 消息跟在 assistant 后面算特殊情况）。裁剪后如果出现两条连续的 user 或 assistant，需要合并或插入空消息。

#### 7. Token 预算分配参考

以 8K 上下文窗口为例：

| 区域 | 预算 | 占比 |
|------|------|------|
| System prompt（静态+动态） | ~2000 tokens | 25% |
| 历史摘要（L1+L2） | ~1000 tokens | 12% |
| L0 对话消息 | ~3000 tokens | 38% |
| LLM 输出预留 | ~2000 tokens | 25% |

### 多段 System 消息与消息顺序

#### 多段 system 无优先级区别

多条 system 消息对模型来说优先级相同，都是"开发者指令"。顺序只影响**注意力分布**，不影响权限等级。

#### 消息顺序 = 时间顺序

`messages[]` 数组的索引顺序即时间线。模型把靠后的消息理解为"更近发生的事"，后出现的信息会覆盖前面的同类信息。

#### 两种效应的协同

模型对上下文的处理存在两种不同层面的效应：

**1. Primacy & Recency Effect（注意力分布）**

作用于整个 context 窗口，呈 U 形曲线——开头和结尾注意力高，中间最容易被忽略。这影响的是"模型有没有注意到这段内容"。

```
注意力强度
  ▲
高│█                                           █
  │ █                                        █
  │  █                                     █
低│    █ █ █ █ █ █ █ █ █ █ █ █ █ █ █ █ █ █
  └──────────────────────────────────────────→
  开头                 中间                 结尾
```

**2. 时间语义（信息权威性）**

模型把数组顺序理解为时间线。当前后信息冲突时，后出现的覆盖先出现的。这影响的是"模型采信哪个版本"。

**结合两者的放置策略：**

| 位置 | 注意力 | 时间语义 | 适合放什么 |
|------|--------|---------|-----------|
| 开头 | 高 | 最早 | 核心规则（需要被看到且持久有效） |
| 中间 | 低 | 中间 | 历史摘要（被忽略问题不大，关键信息在 L0 里） |
| 结尾 | 高 | 最近 | L0 对话消息（既被关注，又是最新的） |

这就是 `buildContext()` 按 L2→L1→L0 顺序组装的原因：
- **System 规则放开头**：享受 primacy 高注意力，且不会被时间语义覆盖（指令不是事实）
- **L2/L1 摘要放中间**：即使注意力低也没关系，它们只是兜底
- **L0 最新消息放结尾**：既享受 recency 高注意力，又符合"最新最权威"的时间语义
