import { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import type { ChatMessage } from '../../types';
import { streamChatSSE, type UiSyncEvent } from '../../services/sse';
import './ChatPanel.css';

interface Props {
  conversationId: string | null;
  onConversationCreated: (id: string) => void;
  onUiSync: (sync: UiSyncEvent) => void;
}

export default function ChatPanel({
  conversationId,
  onConversationCreated,
  onUiSync,
}: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Auto-focus input on mount and after streaming ends
  useEffect(() => {
    if (!isStreaming) {
      inputRef.current?.focus();
    }
  }, [isStreaming]);

  const sendMessage = async () => {
    if (!input.trim() || isStreaming) return;

    const userMsg: ChatMessage = {
      id: Date.now().toString(),
      role: 'user',
      content: input.trim(),
    };

    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setIsStreaming(true);

    // Create conversation if needed
    let convId = conversationId;
    if (!convId) {
      const res = await fetch('/api/conversations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: input.trim().slice(0, 50) }),
      });
      const conv = await res.json();
      convId = conv.id;
      onConversationCreated(convId);
    }

    // Add placeholder for assistant response
    const assistantId = (Date.now() + 1).toString();
    setMessages((prev) => [
      ...prev,
      { id: assistantId, role: 'assistant', content: '', isStreaming: true },
    ]);

    let toolMessages: ChatMessage[] = [];

    abortRef.current = streamChatSSE(convId!, userMsg.content, {
      onContent: (text) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId ? { ...m, content: m.content + text } : m,
          ),
        );
      },
      onToolCall: (tc) => {
        const toolMsg: ChatMessage = {
          id: `tool-${tc.id}`,
          role: 'tool',
          content: '',
          toolName: tc.name,
          toolArgs: tc.arguments,
        };
        toolMessages.push(toolMsg);
        setMessages((prev) => {
          const idx = prev.findIndex((m) => m.id === assistantId);
          const before = prev.slice(0, idx);
          const after = prev.slice(idx);
          return [...before, toolMsg, ...after];
        });
      },
      onToolResult: (tr) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === `tool-${tr.tool_call_id}`
              ? { ...m, toolResult: tr.result }
              : m,
          ),
        );
      },
      onUiSync: (sync) => {
        onUiSync(sync);
      },
      onDone: () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId ? { ...m, isStreaming: false } : m,
          ),
        );
        setIsStreaming(false);
      },
      onError: (error) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? { ...m, content: `Error: ${error}`, isStreaming: false }
              : m,
          ),
        );
        setIsStreaming(false);
      },
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      sendMessage();
    }
  };

  return (
    <div className="chat-panel">
      <div className="chat-header">Chat</div>
      <div className="chat-messages">
        {messages.length === 0 && (
          <div className="chat-empty">
            Ask me anything about<br />the knowledge base.
          </div>
        )}
        {messages.map((msg) => (
          <MessageBubble key={msg.id} message={msg} />
        ))}
        <div ref={messagesEndRef} />
      </div>
      <div className="chat-input-area">
        <textarea
          ref={inputRef}
          className="chat-input"
          autoFocus
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Type a message..."
          rows={2}
          disabled={isStreaming}
        />
        <button
          className="chat-send"
          onClick={sendMessage}
          disabled={isStreaming || !input.trim()}
        >
          Send
        </button>
      </div>
    </div>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const [expanded, setExpanded] = useState(false);

  if (message.role === 'tool') {
    return (
      <div className="message tool-message">
        <div className="tool-header" onClick={() => setExpanded(!expanded)}>
          <span className="tool-icon">⚡</span>
          <span className="tool-name">{message.toolName}</span>
          <span className="tool-toggle">{expanded ? '▼' : '▶'}</span>
        </div>
        {expanded && (
          <div className="tool-details">
            {message.toolArgs && (
              <div className="tool-section">
                <div className="tool-label">Arguments:</div>
                <pre className="tool-code">{formatJson(message.toolArgs)}</pre>
              </div>
            )}
            {message.toolResult && (
              <div className="tool-section">
                <div className="tool-label">Result:</div>
                <pre className="tool-code">
                  {formatJson(message.toolResult)}
                </pre>
              </div>
            )}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className={`message ${message.role}-message`}>
      <div className="message-content">
        {message.role === 'assistant' ? (
          <ReactMarkdown>{message.content}</ReactMarkdown>
        ) : (
          message.content
        )}
        {message.isStreaming && <span className="cursor">▊</span>}
      </div>
    </div>
  );
}

function formatJson(str: string): string {
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
}
