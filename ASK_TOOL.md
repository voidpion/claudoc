# `ask_user` 工具设计方案

## 背景

当前 Agent 遇到歧义时只能用纯文本提问，用户体验差。引入 `ask_user` 工具，让 Agent 能向用户展示结构化的选择题（可点击按钮），支持：
- 多候选消歧（"找到 3 篇文档，要删哪篇？"）
- 破坏性操作确认
- 无选项的自由提问

---

## 核心设计

`ask_user` 是特殊工具：它的"结果"来自用户而非本地执行。AgentLoop 检测到 `ask_user` 后发送 SSE 事件、保存占位结果 `[Waiting for user response]`、**中断循环**。用户点击选项后作为普通消息发送，触发新一轮循环。

### SSE 事件格式

```json
{
  "event": "ask_user",
  "data": {
    "tool_call_id": "toolu_xxx",
    "question": "找到以下文档，你要删除哪篇？",
    "options": [
      {"label": "RAG 入门", "value": "doc-123"},
      {"label": "RAG 进阶", "value": "doc-456"}
    ]
  }
}
```

`options` 为可选字段，省略时为自由提问，用户通过输入框回答。

---

## 修改清单

### 1. 新建 `AskUserTool.java`

路径：`backend/src/main/java/com/claudoc/agent/tools/AskUserTool.java`

- 实现 `AgentTool` 接口，`@Component` 自动注册
- 参数 schema：`question`（必填 string）、`options`（可选 array，每项含 `label` 和 `value`）
- `execute()` 返回 `"[Waiting for user response]"`（防御性实现，正常流程中 AgentLoop 会拦截，不会走到 execute）
- `description()` 明确使用场景：

```
Ask the user a clarifying question when their intent is ambiguous or when you need
them to choose between multiple options. Use when: (1) search returns multiple
candidates and you need the user to pick one, (2) a destructive action is ambiguous
and needs confirmation, (3) you need additional information to proceed. Do NOT use
for simple yes/no confirmations that can be inferred from context.
```

### 2. 修改 `AgentLoop.java`

在 `if (!toolCalls.isEmpty())` 块中，保存 assistant 消息后、执行工具前，先分离 `ask_user`：

```
1. 遍历 toolCalls，分为 regularCalls 和 askUserCall（取最后一个 ask_user）
2. 先执行所有 regularCalls（复用已有的工具执行逻辑，包括 SSE 推送和 ui_sync）
3. 如果有 askUserCall：
   - 解析 arguments 获取 question 和 options
   - 发送 ask_user SSE 事件（含 tool_call_id、question、options）
   - 保存 tool result 消息：content="[Waiting for user response]"
   - break 退出 while 循环
4. 没有 askUserCall → continue（已有逻辑不变）
```

**要点**：先执行其他工具再处理 ask_user，因为问题可能依赖其他工具的结果（如先 search 再问用户选哪个）。

### 3. 修改 `MemoryManager.java` — TOOL_GUIDANCE

在 system prompt 的工具选择指南中添加：

意图映射：
```
- 用户指令有歧义 / 搜索返回多个候选 → ask_user(question, options)
- 破坏性操作目标不明确 → ask_user 确认
```

工作流模板：
```
5. 歧义消解：
   search/retrieve → 多个结果 → ask_user(question, options) → 用户选择 → 执行操作
```

规则：
```
- 仅在真正需要时使用 ask_user，不要过度确认
- 当 ask_user 的结果为 "[Waiting for user response]" 时，停止操作，等待用户回复
```

### 4. 修改前端类型 `frontend/src/types/index.ts`

新增接口：

```typescript
export interface AskUserOption {
  label: string;
  value: string;
}

export interface AskUserEvent {
  tool_call_id: string;
  question: string;
  options?: AskUserOption[];
}
```

`ChatMessage` 扩展：

```typescript
export interface ChatMessage {
  // ... 已有字段 ...
  askUserOptions?: AskUserOption[];   // 选项列表
  askUserResolved?: boolean;          // 用户已选择后设为 true，隐藏按钮
}
```

### 5. 修改 SSE 处理 `frontend/src/services/sse.ts`

`StreamCallbacks` 接口新增（可选，不破坏已有调用方）：

```typescript
onAskUser?: (ask: AskUserEvent) => void;
```

`handleEvent` switch 新增：

```typescript
case 'ask_user':
  try {
    if (callbacks.onAskUser) callbacks.onAskUser(JSON.parse(data));
  } catch {}
  break;
```

### 6. 修改聊天面板 `frontend/src/components/Chat/ChatPanel.tsx`

**6a. onAskUser 回调**

将 question 作为 assistant 消息内容，options 挂到消息上：

```typescript
onAskUser: (ask) => {
  setMessages((prev) =>
    prev.map((m) =>
      m.id === assistantId
        ? { ...m, content: m.content + ask.question, askUserOptions: ask.options, isStreaming: false }
        : m
    )
  );
},
```

**6b. refactor sendMessage**

接受可选 `overrideText` 参数，支持按钮点击直接发送：

```typescript
const sendMessage = async (overrideText?: string) => {
  const text = overrideText || input.trim();
  if (!text || isStreaming) return;
  // ... 已有逻辑，用 text 替代 input.trim() ...
  if (!overrideText) setInput('');
};
```

**6c. handleAskUserResponse**

```typescript
const handleAskUserResponse = (messageId: string, value: string, label: string) => {
  setMessages((prev) =>
    prev.map((m) => m.id === messageId ? { ...m, askUserResolved: true } : m)
  );
  sendMessage(label);
};
```

**6d. MessageBubble 渲染**

assistant 消息检测 `askUserOptions`，渲染为可点击按钮：

```tsx
{message.askUserOptions && !message.askUserResolved && (
  <div className="ask-user-options">
    {message.askUserOptions.map((opt) => (
      <button key={opt.value} className="ask-user-option"
        onClick={() => onOptionClick?.(message.id, opt.value, opt.label)}>
        {opt.label}
      </button>
    ))}
  </div>
)}
```

无 options 时仅显示问题文本，用户通过输入框回答。

### 7. 添加样式 `frontend/src/components/Chat/ChatPanel.css`

```css
.ask-user-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.ask-user-option {
  background: rgba(136, 57, 239, 0.08);
  border: 1px solid rgba(136, 57, 239, 0.2);
  border-radius: 8px;
  padding: 8px 16px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.ask-user-option:hover {
  background: rgba(136, 57, 239, 0.15);
  border-color: var(--accent);
}
```

---

## 完整数据流

```
用户: "删除 RAG 笔记"
  ↓
LLM 调用: search("RAG") + ask_user("找到两篇，删哪篇？", options=[...])
  ↓
后端 AgentLoop:
  1. 保存 assistant 消息（含两个 tool_call）
  2. 执行 search → 发 tool_result SSE → 保存结果
  3. 检测 ask_user → 发 ask_user SSE 事件 → 保存 "[Waiting for user response]" → break
  4. 发 done SSE 事件
  ↓
前端:
  - tool_call 卡片: search ⚡
  - tool_result: 搜索结果
  - tool_call 卡片: ask_user ⚡
  - assistant 消息: "找到两篇，删哪篇？" + [RAG 入门] [RAG 进阶] 按钮
  ↓
用户点击 "RAG 入门":
  - 按钮消失（askUserResolved=true）
  - 发送 "RAG 入门" 作为新用户消息
  ↓
新 AgentLoop:
  - LLM 看到上下文：ask_user 调用 + "[Waiting for user response]" + 用户消息 "RAG 入门"
  - LLM 调用 delete_note("doc-123")
  - 正常执行删除流程
```

---

## 边界情况

| 场景 | 处理方式 |
|------|---------|
| 用户刷新页面（ask_user 未响应） | 按钮消失，但对话历史保留。用户发新消息，LLM 从上下文恢复 |
| 用户不点按钮，直接打字回答 | 正常工作。输入框已启用（isStreaming=false），文本作为用户消息发送 |
| LLM 在一次响应中调用多个 ask_user | 只处理最后一个 |
| LLM 调用 ask_user 同时调用其他工具 | 先执行其他工具，再处理 ask_user |
| 无 options 的自由提问 | 只显示问题文本，无按钮，用户通过输入框回答 |

---

## 验证步骤

1. `mvn compile` 编译通过
2. 重启后端，确认 `ask_user` 出现在工具注册日志
3. 前端测试：知识库有多篇相似文档时，对 Agent 说"删除 RAG 文档"，观察选项按钮渲染
4. 点击按钮后确认 Agent 正确执行对应操作
5. 测试无选项场景：Agent 提出自由问题，用户通过输入框回答
6. 测试页面刷新后的恢复能力
