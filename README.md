# Claudoc

Claudoc 是一个 **Obsidian + Claude 风格的知识库 Agent Demo**，展示如何将 LLM Agent Loop、RAG 检索增强生成、分层记忆压缩、上下文工程等技术整合到一个可交互的 Web 应用中。

**技术栈：** Spring Boot 3.2.5 (Java 17) + React 19 + TypeScript + Vite + H2 内存数据库

---

## 目录

- [1. 快速开始](#1-快速开始)
- [2. 项目结构](#2-项目结构)
- [3. 系统架构](#3-系统架构)
- [4. Agent Loop](#4-agent-loop)
- [5. 上下文工程](#5-上下文工程)
- [6. 记忆与压缩系统](#6-记忆与压缩系统)
- [7. 工具系统](#7-工具系统)
- [8. RAG 检索增强生成](#8-rag-检索增强生成)
- [9. LLM 客户端](#9-llm-客户端)
- [10. 软删除与回收站](#10-软删除与回收站)
- [11. SSE 流式传输](#11-sse-流式传输)
- [12. 数据库设计](#12-数据库设计)
- [13. 前端技术细节](#13-前端技术细节)

---

## 1. 快速开始

### 环境要求

- Java 17+
- Maven 3.8+
- Node.js 18+

### 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端运行在 `http://localhost:7070`，H2 控制台可通过 `http://localhost:7070/h2-console` 访问（JDBC URL: `jdbc:h2:mem:claudoc`，用户名 `sa`，无密码）。

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端运行在 `http://localhost:5173`，Vite 开发服务器自动将 `/api` 请求代理到后端。

### LLM 配置

编辑 `backend/src/main/resources/application.yml` 中的 `llm` 部分：

```yaml
llm:
  provider: anthropic          # anthropic 或 openai
  base-url: https://api.moonshot.cn/anthropic
  api-key: your-api-key
  model: moonshot-v1-8k
  max-tokens: 4096
  temperature: 0.7
```

支持任何兼容 Anthropic 或 OpenAI 协议的 API 端点。

---

## 2. 项目结构

```
claudoc/
├── backend/
│   └── src/main/java/com/claudoc/
│       ├── agent/
│       │   ├── AgentLoop.java          # Agent 循环主逻辑
│       │   ├── AgentTool.java          # 工具接口定义
│       │   ├── ToolRegistry.java       # 工具自动发现与注册
│       │   ├── UiActionTracker.java    # UI 操作追踪（环形缓冲）
│       │   ├── memory/
│       │   │   └── MemoryManager.java  # 记忆管理 + 上下文构建 + 压缩
│       │   └── tools/                  # 10 个工具实现
│       │       ├── CreateNoteTool.java
│       │       ├── ReadNoteTool.java
│       │       ├── UpdateNoteTool.java
│       │       ├── DeleteNoteTool.java
│       │       ├── ListNotesTool.java
│       │       ├── SearchTool.java
│       │       ├── RetrieveTool.java
│       │       ├── GetChunksTool.java
│       │       ├── ListTrashTool.java
│       │       └── RestoreNoteTool.java
│       ├── config/
│       │   ├── AgentConfig.java        # Agent 参数配置
│       │   ├── LlmConfig.java          # LLM 连接配置
│       │   ├── WebConfig.java          # CORS 配置
│       │   └── DataInitializer.java    # 启动时种子数据
│       ├── controller/
│       │   ├── ChatController.java     # 聊天 SSE 接口
│       │   └── KnowledgeBaseController.java  # 知识库 CRUD + 回收站
│       ├── llm/
│       │   ├── LlmClient.java          # LLM 客户端接口
│       │   ├── AnthropicClient.java    # Anthropic 协议实现
│       │   ├── OpenAiCompatibleClient.java   # OpenAI 协议实现
│       │   ├── ChatMessage.java        # 消息 DTO
│       │   ├── ToolCall.java           # 工具调用 DTO
│       │   ├── ToolDefinition.java     # 工具定义（Function Calling 格式）
│       │   └── LlmResponseChunk.java   # 流式响应块
│       ├── model/                      # JPA 实体
│       │   ├── Document.java
│       │   ├── Chunk.java
│       │   ├── Conversation.java
│       │   └── Message.java
│       ├── repository/                 # Spring Data JPA 仓库
│       └── service/
│           ├── KnowledgeBaseService.java    # 知识库业务逻辑
│           ├── ChunkingService.java         # 文本分块
│           ├── EmbeddingService.java        # TF-IDF 嵌入
│           └── VectorSearchService.java     # 向量检索
├── frontend/
│   └── src/
│       ├── App.tsx                     # 三栏布局入口
│       ├── components/
│       │   ├── Sidebar/FileTree.tsx    # 文件树 + 回收站
│       │   ├── Content/MarkdownViewer.tsx   # Markdown 渲染
│       │   └── Chat/ChatPanel.tsx      # 聊天面板
│       ├── services/
│       │   ├── api.ts                  # REST API 封装
│       │   └── sse.ts                  # SSE 流式解析
│       └── types/index.ts             # TypeScript 类型定义
└── ARCH.md                            # 架构设计文档
```

---

## 3. 系统架构

### 整体数据流

```
用户界面（三栏布局）
┌──────────────┬──────────────────┬─────────────────┐
│  文件树       │  文档查看器       │  聊天面板        │
│  (FileTree)  │  (MarkdownViewer)│  (ChatPanel)    │
└──────┬───────┴────────┬─────────┴────────┬────────┘
       │                │                  │
       │ REST API       │ REST API         │ SSE Stream
       ▼                ▼                  ▼
┌─────────────────────────────────────────────────────┐
│               Spring Boot Backend                    │
│                                                      │
│  KnowledgeBaseController    ChatController            │
│         │                       │                    │
│         ▼                       ▼                    │
│  KnowledgeBaseService      AgentLoop ◄──────────┐   │
│    │         │                │                  │   │
│    ▼         ▼                ▼                  │   │
│  Chunking  Embedding    MemoryManager           │   │
│  Service   Service         │     │              │   │
│    │         │             │     ▼              │   │
│    ▼         ▼             │  LlmClient        │   │
│  VectorSearchService       │  (Anthropic/       │   │
│                            │   OpenAI)          │   │
│                            ▼                    │   │
│                       ToolRegistry ──► AgentTool │   │
│                            │          (10 tools)│   │
│                            └────────────────────┘   │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │          H2 In-Memory Database                │   │
│  │  document │ chunk │ conversation │ message    │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 核心交互流程

1. 用户在聊天面板发送消息
2. `ChatController` 接收请求，自动创建/复用会话，创建 `SseEmitter`
3. `AgentLoop` 在线程池中异步执行循环
4. `MemoryManager` 构建完整上下文（system prompt + 历史消息）
5. `LlmClient` 流式调用 LLM，逐 token 推送给前端
6. 若 LLM 返回 tool_call，`ToolRegistry` 执行工具，结果写回上下文，继续循环
7. 循环结束后异步触发记忆压缩

---

## 4. Agent Loop

**核心文件：** `backend/src/main/java/com/claudoc/agent/AgentLoop.java`

Agent Loop 是整个系统的心脏，实现了 ReAct（Reasoning + Acting）模式的多轮工具调用循环。

### 执行流程

```
用户消息
    │
    ▼
保存用户消息 (MemoryManager.saveMessage)
    │
    ▼
┌─► 构建上下文 (MemoryManager.buildContext)
│       │
│       ▼
│   调用 LLM (llmClient.chatStream)
│       │
│       ├── 收到文本内容 ──► 通过 SSE 推送给前端
│       │
│       └── 收到 tool_call ──► 通过 SSE 推送给前端
│               │
│               ▼
│           执行工具 (ToolRegistry.executeTool)
│               │
│               ▼
│           保存工具结果 + SSE 推送
│               │
│               └──► 继续循环 ◄────────────┘
│
│   [终止条件]
│   ├── 有文本内容 + 无 tool_call → 保存 assistant 消息，退出
│   ├── 无文本 + 无 tool_call → 安全退出
│   └── 迭代次数 ≥ 10 → 发送警告，强制退出
│
▼
发送 done 事件，关闭 SSE
    │
    ▼
异步触发记忆压缩 (MemoryManager.compressIfNeeded)
```

### 关键设计

- **最大迭代次数：** `MAX_TOOL_ITERATIONS = 10`，防止 LLM 陷入无限工具调用循环
- **异步执行：** 使用 `Executors.newCachedThreadPool()` 在独立线程中运行，不阻塞 HTTP 请求线程
- **SseEmitter 超时：** 300 秒（5 分钟），覆盖复杂的多轮工具调用场景
- **Tool Call 持久化：** assistant 消息的 `toolCallsJson` 字段序列化 `List<ToolCall>`，确保多轮对话中 `tool_call_id` 的对应关系不丢失
- **异步压缩：** 循环结束后在独立线程中执行记忆压缩，不影响用户体验

### 消息保存策略

| 消息类型 | role | 附加字段 |
|---------|------|---------|
| 用户输入 | user | — |
| LLM 回复（纯文本） | assistant | — |
| LLM 回复（含工具调用） | assistant | toolCallsJson = 序列化的 `List<ToolCall>` |
| 工具执行结果 | tool | toolCallId, toolName |

---

## 5. 上下文工程

**核心文件：** `backend/src/main/java/com/claudoc/agent/memory/MemoryManager.java`

上下文工程是 Agent 效果的核心——精心设计的 system prompt 结构、动态信息注入、历史消息分层，共同决定了 Agent 在每一轮对话中"看到"什么。

### 5.1 System Prompt 分层结构

`MemoryManager.buildContext()` 按固定顺序组装上下文，每一层都有明确的职责：

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: IDENTITY_AND_RULES                [静态]          │
│  ── 身份定义 + 行为准则 + 路径规则                             │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: TOOL_GUIDANCE                     [静态]          │
│  ── 意图→工具映射 + 工作流模板 + 使用规则                       │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: KB_OVERVIEW                       [动态·每次生成]   │
│  ── 知识库文档列表（path + title + id）                       │
├─────────────────────────────────────────────────────────────┤
│  Layer 4: UI_CONTEXT                        [动态·每次生成]   │
│  ── 最近用户操作（打开文档、操作回收站等）                       │
├─────────────────────────────────────────────────────────────┤
│  Layer 5: L2 Global Summary                 [动态·压缩产物]   │
│  ── 全局对话摘要                                             │
├─────────────────────────────────────────────────────────────┤
│  Layer 6: L1 Recent Summaries               [动态·压缩产物]   │
│  ── 近期轮次摘要（可能多条）                                   │
├─────────────────────────────────────────────────────────────┤
│  Layer 7: L0 Raw Messages                   [动态·原始对话]   │
│  ── user / assistant(+tool_calls) / tool 消息                │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 Layer 1：IDENTITY_AND_RULES

```java
private static final String IDENTITY_AND_RULES = """
    You are Claudoc, an AI assistant integrated with a knowledge base system.
    You help users manage, search, and understand their notes.
    ...
    ## Path and Directory Rules
    - Directories do NOT exist independently. They are created implicitly by note paths.
    - When the user asks to "create a directory/folder", create an initial note under that path.
    - The '/_memory/' path is ONLY for the agent's own memory notes.
      NEVER put user-requested content under '/_memory/'.
    """;
```

**设计要点：**
- 明确 Agent 身份和核心行为（引用来源、主动存储记忆）
- **路径规则**是从实际使用中发现的问题驱动的：用户说"建一个日志目录"，Agent 曾经错误地在 `/_memory/` 下创建了一个笔记。通过显式规则纠正这一行为

### 5.3 Layer 2：TOOL_GUIDANCE

```java
private static final String TOOL_GUIDANCE = """
    ## Tool Selection Guide
    Choose the right tool based on the user's intent:
    - "What notes are there?" / "Show files" → list_notes
    - Question about a topic → retrieve (semantic search) → optionally read_note
    - "Find notes containing X" → search (keyword match)
    - "Delete note X" → delete_note (moves to trash)
    - "Restore note" / "Undo delete" → list_trash → restore_note
    ...
    ## Common Workflows
    1. Answering knowledge questions:
       retrieve(query) → read_note(doc_id) for full context → answer with citation
    2. Creating a new note:
       search(topic) to check existence → create_note(path, title, content)
    ...
    ## Important Rules
    - Prefer 'retrieve' over 'search' for open-ended questions (semantic > keyword).
    - Never guess document IDs — always get them from tool results.
    - delete_note is a soft delete (trash). Notes can be restored with restore_note.
    """;
```

**设计要点：**
- **意图→工具映射表**：将自然语言意图映射到具体工具，减少 Agent 的"选择困难"
- **工作流模板**：预定义多步骤操作链（如"先 retrieve 再 read_note"），教会 Agent 组合使用工具
- **防错规则**：明确 `retrieve` 和 `search` 的使用场景区分，避免 Agent 总是用关键词搜索

### 5.4 Layer 3：动态 KB 概览

```java
private String buildKbOverview() {
    List<Document> docs = documentRepository.findAll();
    if (docs.isEmpty()) return "The knowledge base is empty.";
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%d documents:\n", docs.size()));
    for (Document doc : docs) {
        sb.append(String.format("- %s \"%s\" (id: %s)\n", doc.getPath(), doc.getTitle(), doc.getId()));
    }
    return sb.toString();
}
```

**注入效果示例：**
```
[Knowledge Base Overview]
5 documents:
- /notes/welcome.md "Welcome to Claudoc" (id: abc123)
- /notes/guide/markdown-syntax.md "Markdown Syntax" (id: def456)
- /_memory/system-init.md "System Initialization" (id: ghi789)
```

**设计要点：**
- 用户问"知识库里有什么"时，Agent 无需调用 `list_notes` 工具就能直接回答
- 减少不必要的工具调用轮次，降低延迟和 token 消耗
- 每次构建上下文时从数据库实时查询，保证数据一致性

### 5.5 Layer 4：UI 操作上下文

**核心文件：** `backend/src/main/java/com/claudoc/agent/UiActionTracker.java`

```java
@Component
public class UiActionTracker {
    private static final int MAX_ACTIONS = 10;
    private final LinkedList<UiAction> actions = new LinkedList<>();

    public record UiAction(String type, String detail, String time) {}

    public synchronized void record(String type, String detail) {
        actions.addLast(new UiAction(type, detail, LocalDateTime.now().format(TIME_FMT)));
        if (actions.size() > MAX_ACTIONS) {
            actions.removeFirst();
        }
    }
}
```

**埋点位置（`KnowledgeBaseController`）：**

| API 端点 | 事件类型 | 说明 |
|---------|---------|------|
| `GET /notes/{id}` | `open_document` | 记录文档路径和标题 |
| `GET /trash` | `expand_trash` | 用户查看了回收站 |
| `POST /trash/{id}/restore` | `restore_note` | 记录恢复的文档信息 |
| `DELETE /trash/{id}` | `permanent_delete` | 记录删除的文档 ID |

**注入效果示例：**
```
## Recent User Actions
- [10:03] open_document: /design/arch.md "架构设计"
- [10:04] open_document: /design/api.md "API设计"
- [10:05] open_document: /design/db.md "数据库设计"
- [10:06] expand_trash
```

**设计要点：**
- **后端 API 层埋点**，而非前端记录——零额外网络开销，利用已有的 API 调用
- **内存环形缓冲**（最近 10 条），会话级生命周期，不持久化
- **解决指代消歧**：用户说"帮我总结一下这个"，Agent 能从 UI 上下文中知道"这个"是哪篇文档
- **意图链推断**：连续打开多篇文档后问"对比一下差异"，Agent 能推断出需要对比的是哪些文档

---

## 6. 记忆与压缩系统

### 6.1 三级记忆架构

```
时间线 ──────────────────────────────────────────────────────►

│◄────── 最早的对话 ──────►│◄── 较早 ──►│◄──── 最近的对话 ────►│
│                          │            │                      │
│   ┌─────────────┐        │  ┌──────┐  │  ┌────────────────┐ │
│   │ L2 全局摘要  │        │  │ L1   │  │  │  L0 原始消息    │ │
│   │ (1 条)      │        │  │ 摘要  │  │  │  (多条)        │ │
│   │             │        │  │(多条) │  │  │                │ │
│   └─────────────┘        │  └──────┘  │  └────────────────┘ │
│                          │            │                      │
│  压缩比: 最高             │  中等      │  无压缩（原始保留）    │
```

| 层级 | 存储内容 | 触发条件 | 存储方式 |
|------|---------|---------|---------|
| **L0** | 原始对话消息 | 始终保留最近部分 | `message` 表，`level=0` |
| **L1** | 轮次摘要 | L0 估算 token 数 > `l0-max-tokens`（默认 4000） | `message` 表，`level=1`，role=system |
| **L2** | 全局摘要 | L1 条数 > `l1-max-count`（默认 5） | `message` 表，`level=2`，role=system |

### 6.2 Token 估算

```java
private int estimateTokens(List<Message> messages) {
    return messages.stream()
            .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length() / 4)
            .sum();
}
```

采用 `字符数 / 4` 的简单近似。对于中英文混合内容，这个比率是一个合理的折中（英文约 4 字符/token，中文约 1-2 字符/token）。

### 6.3 轮次边界分割

```java
private int findRoundBoundary(List<Message> messages) {
    int target = messages.size() / 2;

    // 从中点向前扫描，找到 role=user 的消息
    for (int i = target; i > 0; i--) {
        if ("user".equals(messages.get(i).getRole())) {
            return i;
        }
    }

    // 向前没找到，向后扫描
    for (int i = target + 1; i < messages.size() - 1; i++) {
        if ("user".equals(messages.get(i).getRole())) {
            return i;
        }
    }

    // 兜底：直接取中点
    return target;
}
```

**为什么需要轮次边界分割？**

一个完整的对话"轮次"包含：
```
user: "搜索关于 RAG 的笔记"
assistant: [tool_call: retrieve("RAG")]
tool: [结果: 找到 3 篇相关文档...]
assistant: [tool_call: read_note("doc-123")]
tool: [结果: 文档完整内容...]
assistant: "根据知识库中的文档，RAG 是..."
```

如果在 `tool_call` 和 `tool_result` 之间截断，会导致：
- 上下文中出现没有对应结果的 tool_call（LLM 困惑）
- 工具调用链的因果关系断裂
- Anthropic API 要求 tool_use 必须有对应的 tool_result，否则报 400 错误

因此，压缩时必须在 `user` 消息的边界处分割，确保每个轮次的完整性。

### 6.4 L0 → L1 压缩流程

```
1. 触发：estimateTokens(L0) > 4000

2. 分割：findRoundBoundary() 找到中点附近的 user 消息
   ┌─────────────────────┬─────────────────────┐
   │  要压缩的旧消息       │  保留的新消息         │
   │  (splitPoint 之前)   │  (splitPoint 之后)   │
   └─────────────────────┴─────────────────────┘

3. 预处理：截断过长的 tool result（>500 字符加 "...[truncated]"）

4. 拼接为文本：
   "user: 搜索关于 RAG 的笔记\n\n
    assistant: [tool call]\n\n
    tool: 找到 3 篇文档...[truncated]\n\n
    assistant: 根据知识库...\n\n"

5. 调用 LLM 总结（使用 COMPRESS_PROMPT）

6. 保存摘要为 L1 消息（level=1, role=system）

7. 标记旧消息为 archived=true
```

### 6.5 L1 → L2 压缩流程

```
1. 触发：count(L1) > 5

2. 拼接所有现有摘要：
   "[Previous Global Summary]\n{旧 L2 内容}\n\n
    [Summary]\n{L1-1 内容}\n\n
    [Summary]\n{L1-2 内容}\n\n
    ..."

3. 调用 LLM 总结为单一全局摘要

4. 保存为新的 L2 消息（level=2, role=system）

5. 标记所有旧 L1 和旧 L2 为 archived=true
```

### 6.6 压缩提示词

```java
private static final String COMPRESS_PROMPT = """
    Summarize the following conversation concisely, preserving:
    - All key facts, decisions, and user preferences
    - Document IDs and paths that were referenced
    - Technical details (names, numbers, configurations)
    - What tools were called and their key results (not full output)
    Omit verbose tool output details. Output only the summary, no preamble.
    """;
```

**设计要点：**
- 明确要求保留文档 ID 和路径——这是知识库场景下最关键的上下文信息
- 保留工具调用摘要但省略详细输出——平衡信息密度和 token 消耗
- 保留用户偏好和决策——支持长对话中的行为一致性

### 6.7 Tool Call 持久化

多轮工具调用的一个关键问题：assistant 消息中的 `tool_use` block 包含 `id` 字段，后续的 `tool_result` 必须通过 `tool_use_id` 引用这个 id。如果 assistant 消息只保存了文本内容而丢失了 tool_call 信息，重建上下文时 LLM 会报错。

**解决方案：**

```java
// 保存时序列化
public void saveMessage(..., List<ToolCall> toolCalls) {
    String toolCallsJson = null;
    if (toolCalls != null && !toolCalls.isEmpty()) {
        toolCallsJson = objectMapper.writeValueAsString(toolCalls);
    }
    // 存入 message.tool_calls_json 列
}

// 恢复时反序列化
private ChatMessage toChatMessage(Message msg) {
    if ("assistant".equals(msg.getRole()) && msg.getToolCallsJson() != null) {
        List<ToolCall> tcs = objectMapper.readValue(
                msg.getToolCallsJson(), new TypeReference<List<ToolCall>>() {});
        cm.setToolCalls(tcs);
    }
}
```

数据库中 `message` 表的 `tool_calls_json` 列（CLOB 类型）存储完整的 JSON：

```json
[{
  "id": "toolu_abc123",
  "type": "function",
  "function": {
    "name": "retrieve",
    "arguments": "{\"query\":\"RAG\"}"
  }
}]
```

---

## 7. 工具系统

### 7.1 架构

```java
public interface AgentTool {
    String name();                              // 工具名称
    String description();                       // LLM 看到的描述
    Map<String, Object> parametersSchema();      // JSON Schema 参数定义
    String execute(Map<String, Object> args);    // 执行逻辑
}
```

`ToolRegistry` 利用 Spring 的依赖注入自动发现所有 `@Component` 实现的 `AgentTool`：

```java
@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> agentTools) {
        agentTools.forEach(t -> tools.put(t.name(), t));
    }

    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(t -> ToolDefinition.of(t.name(), t.description(), t.parametersSchema()))
                .toList();
    }
}
```

新增工具只需创建一个实现 `AgentTool` 的 `@Component` 类，无需修改任何注册代码。

### 7.2 工具清单

| 工具 | 功能 | 参数 |
|------|------|------|
| `create_note` | 创建笔记 | path, title, content（全部必填） |
| `read_note` | 读取笔记 | id 或 path（二选一） |
| `update_note` | 更新笔记 | id（必填）, title, content（可选） |
| `delete_note` | 软删除（移到回收站） | id（必填） |
| `list_notes` | 列出目录树 | 无参数 |
| `search` | 关键词搜索（SQL LIKE） | keyword（必填） |
| `retrieve` | 语义检索（向量相似度） | query（必填）, top_k（可选，默认 5） |
| `get_chunks` | 查看文档分块（调试） | document_id（必填） |
| `list_trash` | 列出回收站内容 | 无参数 |
| `restore_note` | 从回收站恢复 | id（必填） |

### 7.3 工具描述规范

每个工具的 `description()` 遵循统一的结构化格式，帮助 LLM 准确选择工具：

```java
// SearchTool.java
public String description() {
    return "Search notes by keyword (SQL LIKE match on title and content). "
            + "Use when: user asks for exact term lookup or 'find notes containing X'. "
            + "Do NOT use for semantic/meaning-based queries — use 'retrieve' instead. "
            + "Returns: list of matching documents with id, path, title, and content snippet.";
}
```

**三段式结构：**
1. **功能描述**：这个工具做什么
2. **Use when / Do NOT use**：何时用、何时不用（减少工具选择错误）
3. **Returns**：返回什么格式的结果（帮助 LLM 规划后续步骤）

---

## 8. RAG 检索增强生成

### 8.1 分块（ChunkingService）

**文件：** `backend/src/main/java/com/claudoc/service/ChunkingService.java`

```
原始文档
    │
    ▼
按段落分割（\n\n+）
    │
    ▼
累加段落直到超过 maxChunkSize (500 字符)
    │
    ├── 未超过 → 继续累加
    │
    └── 超过 → 保存当前 chunk
                │
                ▼
            提取最后一句作为重叠部分
                │
                ▼
            新 chunk 以重叠句子开头
```

**重叠机制：** 取上一个 chunk 的最后一句（按 `[.!?]` 和中日文句号 `。！？` 分割），带入下一个 chunk 的开头。这确保了跨 chunk 边界的语义连续性。

**配置：**
```yaml
agent:
  chunking:
    max-chunk-size: 500    # 每个 chunk 的最大字符数
    overlap-sentences: 1    # 重叠的句子数
```

### 8.2 嵌入（EmbeddingService）

**文件：** `backend/src/main/java/com/claudoc/service/EmbeddingService.java`

采用 **TF-IDF + 哈希降维** 方案，不依赖外部嵌入模型。

#### 分词流程

```
输入文本: "RAG是一种检索增强生成技术"
    │
    ▼
按空白+标点分割: ["RAG是一种检索增强生成技术"]
    │
    ▼
CJK 字符逐字拆分: ["rag", "是", "一", "种", "检", "索", "增", "强", "生", "成", "技", "术"]
    │
    ▼
过滤: 非 CJK 的 token 长度 < 2 → 过滤掉
最终: ["rag", "是", "一", "种", "检", "索", "增", "强", "生", "成", "技", "术"]
```

#### TF-IDF 计算

```
TF（词频）= 0.5 + 0.5 × (词在文本中出现次数 / 文本中最高词频)
    │
    │  增强 TF 公式，避免长文档的词频偏差
    │
IDF（逆文档频率）= log(1 + (总文档数 + 1) / (1 + 包含该词的文档数))
    │
    │  DF 计数器在内存中维护（ConcurrentHashMap）
    │  随文档创建/删除增量更新
    │
向量[hash(word) % 256] += TF × IDF
    │
    │  哈希降维：将任意词汇映射到 256 维固定空间
    │  Math.abs(word.hashCode()) % 256
    │
L2 归一化 → 最终 256 维单位向量
```

#### 向量存储

向量序列化为 JSON 字符串存入 `chunk.vector` 列：

```json
[0.0, 0.123, 0.0, 0.456, ...]  // 256 个浮点数
```

### 8.3 向量检索（VectorSearchService）

**文件：** `backend/src/main/java/com/claudoc/service/VectorSearchService.java`

```java
// 内存索引
private final ConcurrentHashMap<String, double[]> index = new ConcurrentHashMap<>();

// 暴力余弦相似度检索
public List<ChunkScore> retrieve(double[] queryVector, int topK) {
    PriorityQueue<ChunkScore> heap = new PriorityQueue<>(
            Comparator.comparingDouble(ChunkScore::score));  // 最小堆

    for (var entry : index.entrySet()) {
        double score = cosineSimilarity(queryVector, entry.getValue());
        heap.offer(new ChunkScore(entry.getKey(), score));
        if (heap.size() > topK) heap.poll();  // 保留 top K
    }

    return heap.stream()
            .sorted(Comparator.comparingDouble(ChunkScore::score).reversed())
            .toList();
}
```

**完整检索流程：**

```
用户问题: "什么是 RAG？"
    │
    ▼
EmbeddingService.embed("什么是 RAG？") → 256 维查询向量
    │
    ▼
VectorSearchService.retrieve(queryVector, topK=5)
    │  暴力遍历所有 chunk 向量
    │  计算余弦相似度
    │  最小堆保留 Top 5
    │
    ▼
ChunkScore[] → 查找 Chunk 实体 → 查找 Document 实体
    │
    ▼
RetrieveResult[](document, chunk, score)
```

---

## 9. LLM 客户端

### 9.1 接口定义

```java
public interface LlmClient {
    Flux<LlmResponseChunk> chatStream(List<ChatMessage> messages, List<ToolDefinition> tools);
}
```

返回值是 Reactor 的 `Flux`（响应式流），`LlmResponseChunk` 有三种类型：

| Type | 内容 | 说明 |
|------|------|------|
| `CONTENT` | `chunk.getContent()` 返回文本片段 | LLM 生成的文本 token |
| `TOOL_CALL` | `chunk.getToolCall()` 返回完整的工具调用 | 包含 id, name, arguments |
| `DONE` | 无内容 | 流结束标记 |

### 9.2 AnthropicClient（主用）

**文件：** `backend/src/main/java/com/claudoc/llm/AnthropicClient.java`

使用 Java 标准库的 `java.net.http.HttpClient`（而非 Spring WebClient），原因是 WebClient 在某些代理场景下的 chunked transfer encoding 会导致请求体被截断。

#### 请求构建

```
POST {baseUrl}/v1/messages
Headers:
  x-api-key: {apiKey}
  anthropic-version: 2023-06-01
  User-Agent: claude-cli/2.1.80
  Content-Type: application/json

Body:
{
  "model": "moonshot-v1-8k",
  "max_tokens": 4096,
  "stream": true,
  "system": [{"type": "text", "text": "...所有 system 消息拼接..."}],
  "messages": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": [
      {"type": "text", "text": "..."},
      {"type": "tool_use", "id": "toolu_xxx", "name": "retrieve", "input": {...}}
    ]},
    {"role": "user", "content": [
      {"type": "tool_result", "tool_use_id": "toolu_xxx", "content": "..."}
    ]}
  ],
  "tools": [
    {"name": "retrieve", "description": "...", "input_schema": {...}}
  ]
}
```

**关键处理：**
- **system 字段**：所有 system role 消息合并为一个文本，以数组格式发送 `[{"type":"text","text":"..."}]`（而非字符串格式，某些 API 网关不接受字符串格式）
- **消息合并**：Anthropic 要求 user/assistant 严格交替。连续的 user 消息或连续的 assistant 消息会被合并为一条，content 字段变为数组
- **tool_result 处理**：tool role 的消息转换为 `user` role 下的 `tool_result` content block

#### SSE 解析

```
event: content_block_start
data: {"index": 0, "content_block": {"type": "text"}}

event: content_block_delta
data: {"delta": {"type": "text_delta", "text": "Hello"}}

event: content_block_start
data: {"index": 1, "content_block": {"type": "tool_use", "id": "toolu_xxx", "name": "retrieve"}}

event: content_block_delta
data: {"delta": {"type": "input_json_delta", "partial_json": "{\"query\":"}}

event: content_block_delta
data: {"delta": {"type": "input_json_delta", "partial_json": "\"RAG\"}"}}

event: message_stop
data: {}
```

- `content_block_start` with `type=tool_use`：创建 `ToolCall` 对象
- `content_block_delta` with `text_delta`：发射 `CONTENT` chunk
- `content_block_delta` with `input_json_delta`：追加 JSON 片段到 pending tool call
- `message_stop`：发射所有累积的 `TOOL_CALL` chunk + `DONE` chunk

### 9.3 OpenAiCompatibleClient（备用）

**文件：** `backend/src/main/java/com/claudoc/llm/OpenAiCompatibleClient.java`

使用 Spring WebClient，发送 OpenAI 格式的请求到 `{baseUrl}/chat/completions`，解析 `data: [DONE]` 终止。作为备用客户端，当 `llm.provider=openai` 时启用。

---

## 10. 软删除与回收站

### 数据层

```sql
-- document 表新增 deleted_at 列
CREATE TABLE IF NOT EXISTS document (
    ...
    deleted_at  TIMESTAMP    -- NULL = 正常, 非 NULL = 已删除
);
```

所有查询自动过滤已删除文档：

```java
@Query("SELECT d FROM Document d WHERE d.path = :path AND d.deletedAt IS NULL")
Optional<Document> findByPath(String path);

@Query("SELECT DISTINCT d.path FROM Document d WHERE d.deletedAt IS NULL ORDER BY d.path")
List<String> findAllPaths();
```

### 操作流程

```
正常文档                              回收站
┌──────────┐    deleteNote()     ┌──────────────┐
│ document │ ──────────────────► │ deleted_at   │
│ deleted_at│    设置时间戳        │ = 2024-03-21 │
│ = NULL   │    移除向量索引      │ 无搜索索引    │
└──────────┘                     └──────────────┘
     ▲          restoreNote()          │
     │ ◄────────────────────────────── │
     │     清除时间戳                    │
     │     重建向量索引                  │
                                       │
                              permanentDelete()
                                       │
                                       ▼
                                   彻底删除
```

**关键设计：**
- `deleteNote`：设置 `deleted_at` 时间戳 + 移除 chunk 和向量索引（确保已删除文档不会出现在搜索/检索结果中）
- `restoreNote`：清除 `deleted_at` + 重新分块、嵌入、建立向量索引
- `permanentDelete`：硬删除文档记录（chunk 通过外键级联删除）

### API 端点

| 方法 | 路径 | 功能 |
|------|------|------|
| `GET` | `/api/trash` | 列出回收站 |
| `POST` | `/api/trash/{id}/restore` | 恢复文档 |
| `DELETE` | `/api/trash/{id}` | 永久删除 |

---

## 11. SSE 流式传输

### 后端事件定义

| 事件名 | 数据格式 | 说明 |
|--------|---------|------|
| `content` | `{"text": "Hello "}` | LLM 生成的文本片段 |
| `tool_call` | `{"id": "...", "name": "retrieve", "arguments": "..."}` | 工具调用 |
| `tool_result` | `{"tool_call_id": "...", "name": "retrieve", "result": "..."}` | 工具执行结果 |
| `ui_sync` | `{"refresh": ["tree", "document"], "documentId": "abc"}` | UI 同步通知 |
| `done` | `{}` | 流结束 |
| `error` | `{"error": "message"}` | 错误 |

**content 事件使用 JSON 包装**的原因：SSE 规范中 `data:` 后的内容会被 `trim()`，导致 LLM 输出的前导/尾随空格丢失（如 `" How"` 变成 `"How"`）。通过 `{"text": " How "}` 包装，空白字符被 JSON 字符串保护。

### UI 同步机制（ui_sync）

Agent 通过工具修改知识库后（创建、更新、删除、恢复笔记），后端自动发送 `ui_sync` 事件通知前端刷新对应的 UI 区域。

**后端：工具→刷新目标映射（`AgentLoop.TOOL_REFRESH_MAP`）**

| 工具 | 刷新目标 |
|------|---------|
| `create_note` | `["tree"]` |
| `update_note` | `["tree", "document"]` |
| `delete_note` | `["tree", "trash"]` |
| `restore_note` | `["tree", "trash"]` |
| `list_trash` | `["trash"]` |

工具执行后，`AgentLoop` 根据映射表构建 `ui_sync` 事件：

```java
List<String> refreshTargets = TOOL_REFRESH_MAP.get(toolName);
if (refreshTargets != null) {
    Map<String, Object> syncData = new HashMap<>();
    syncData.put("refresh", refreshTargets);
    if (args.containsKey("id")) {
        syncData.put("documentId", args.get("id"));
    }
    emitter.send(SseEmitter.event().name("ui_sync").data(...));
}
```

**前端：精确刷新**

`App.tsx` 中的 `handleUiSync` 根据 `refresh` 数组决定刷新什么：

- `tree` → 重新加载文件树（`fetchTree()`）
- `document` → 如果 `documentId` 等于当前打开的文档，重新加载文档内容
- `trash` → 递增 `trashVersion`，触发 FileTree 中回收站列表的重新加载

**设计要点：**
- **后端决定刷新什么**，前端不需要维护"哪些工具会改数据"的硬编码列表
- **精确刷新**而非全量刷新——`update_note` 只在用户正好在看那篇文档时才重载内容
- **未来扩展零成本**——新增工具时只需在 `TOOL_REFRESH_MAP` 加一行，前端无需改动

### 前端 SSE 解析

```typescript
// services/sse.ts
export function streamChatSSE(
    conversationId: string,
    message: string,
    callbacks: StreamCallbacks
): AbortController {
    const controller = new AbortController();

    fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId, message }),
        signal: controller.signal,
    }).then(async (response) => {
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let currentEvent = 'message';

        while (true) {
            const { done, value } = await reader.read();
            if (done) { callbacks.onDone(); break; }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    currentEvent = line.slice(6).trim();
                } else if (line.startsWith('data:')) {
                    handleEvent(currentEvent, line.slice(5).trim(), callbacks);
                }
            }
        }
    });

    return controller;  // 支持取消
}
```

使用 `ReadableStream` 手动解析 SSE 协议，而非 `EventSource` API——因为 `EventSource` 只支持 GET 请求，而聊天接口需要 POST。

---

## 12. 数据库设计

### ER 图

```
┌──────────────┐       ┌──────────────┐
│  conversation │       │   document   │
│──────────────│       │──────────────│
│  id (PK)     │       │  id (PK)     │
│  title       │       │  path (UQ)   │
│  created_at  │       │  title       │
└──────┬───────┘       │  content     │
       │               │  created_at  │
       │ 1:N           │  updated_at  │
       │               │  deleted_at  │  ◄── 软删除标记
       ▼               └──────┬───────┘
┌──────────────┐              │
│   message    │              │ 1:N (CASCADE)
│──────────────│              │
│  id (PK)     │              ▼
│  conversation│       ┌──────────────┐
│  _id (FK)    │       │    chunk     │
│  role        │       │──────────────│
│  content     │       │  id (PK)     │
│  tool_call_id│       │  document_id │
│  tool_name   │       │    (FK)      │
│  tool_calls  │       │  content     │
│  _json       │       │  chunk_index │
│  level       │  ◄── 记忆层级     │  vector      │  ◄── 256维 TF-IDF 向量 (JSON)
│  archived    │  ◄── 已压缩标记   │  created_at  │
│  created_at  │       └──────────────┘
└──────────────┘
```

### message 表的特殊字段

| 字段 | 用途 |
|------|------|
| `level` | 记忆层级：0=原始消息，1=L1 摘要，2=L2 全局摘要 |
| `archived` | 已被压缩的消息标记为 `true`，不再加载到上下文中 |
| `tool_call_id` | tool role 消息关联到对应的 tool_use（Anthropic 协议要求） |
| `tool_name` | 工具名称，用于 tool role 消息 |
| `tool_calls_json` | assistant 消息的工具调用列表（JSON 序列化），确保多轮对话的 tool_call_id 对齐 |

### 数据生命周期

```
                   H2 内存数据库
应用启动 ──► DataInitializer 创建 5 篇种子文档
                    │
                    ▼
              用户正常使用
              创建/编辑/删除文档
              对话产生消息
              记忆自动压缩
                    │
                    ▼
应用关闭 ──► 所有数据丢失（内存数据库）
应用重启 ──► DataInitializer 重新创建种子文档
```

---

## 13. 前端技术细节

### 三栏布局

```css
.app {
    display: flex;
    height: 100vh;
}
.sidebar  { width: 260px; }    /* 固定宽度 */
.content  { flex: 1; }          /* 自适应 */
.chat     { width: 400px; }     /* 固定宽度 */
```

### Catppuccin Latte 浅色主题

使用 CSS 自定义属性定义完整的颜色系统：

```css
:root {
    --bg-base: #ffffff;
    --bg-mantle: #f5f5f7;
    --bg-crust: #eeeef0;
    --text: #1e1e2e;
    --text-muted: #4c4f69;
    --accent: #8839ef;        /* 紫色主色调 */
    --accent-green: #40a02b;  /* 成功/恢复 */
    --accent-red: #d20f39;    /* 危险/删除 */
}
```

### ChatPanel 消息处理

```
用户点击 Send
    │
    ├── 1. 添加 user 消息到 messages 数组
    │
    ├── 2. 自动创建 conversation（如果不存在）
    │       POST /api/conversations
    │
    ├── 3. 添加 assistant 占位消息 (content="", isStreaming=true)
    │
    └── 4. 开始 SSE 流
            │
            ├── onContent(text) → 追加到 assistant 消息
            │
            ├── onToolCall(tc) → 在 assistant 消息前插入 tool 消息
            │
            ├── onToolResult(tr) → 更新对应 tool 消息的结果
            │       如果是 create/update/delete_note → 刷新文件树
            │
            ├── onDone() → 清除 isStreaming 标记
            │
            └── onError(err) → 显示错误信息
```

### IME 中文输入兼容

```typescript
const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        e.preventDefault();
        sendMessage();
    }
};
```

`e.nativeEvent.isComposing` 检查避免了中文/日文输入法在选字时按 Enter 触发消息发送的问题。

### Markdown 渲染

使用 `react-markdown` + `react-syntax-highlighter`（Prism + oneDark 主题）。自定义 `code` 组件：有语言标记的代码块使用语法高亮，行内代码保持默认样式。

### 文件树回收站

回收站面板内嵌在 `file-tree-content` 内部（而非独立的 flex 子项），与文件树一起滚动，避免布局撑开问题。每个回收站条目在 hover 时显示"恢复"和"永久删除"操作按钮。
