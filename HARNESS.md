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
