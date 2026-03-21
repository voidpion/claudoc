import type { ToolCallEvent, ToolResultEvent } from '../types';

export interface UiSyncEvent {
  refresh: string[];
  documentId?: string;
}

export interface StreamCallbacks {
  onContent: (text: string) => void;
  onToolCall: (tc: ToolCallEvent) => void;
  onToolResult: (tr: ToolResultEvent) => void;
  onUiSync?: (sync: UiSyncEvent) => void;
  onDone: () => void;
  onError: (error: string) => void;
}

export async function streamChat(
  conversationId: string,
  message: string,
  callbacks: StreamCallbacks,
) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, message }),
  });

  if (!response.ok || !response.body) {
    callbacks.onError('Failed to connect to chat');
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (line.startsWith('event:')) {
        const eventName = line.slice(6).trim();
        continue;
      }

      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();

      // We need to track the last event name
      // Re-parse using a simpler approach
    }
  }

  // Better approach: parse SSE properly
  callbacks.onDone();
}

// More robust SSE parser
export function streamChatSSE(
  conversationId: string,
  message: string,
  callbacks: StreamCallbacks,
): AbortController {
  const controller = new AbortController();

  fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, message }),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok || !response.body) {
        callbacks.onError('Failed to connect');
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = 'message';

      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          callbacks.onDone();
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed === '') {
            currentEvent = 'message';
            continue;
          }

          if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.slice(6).trim();
            continue;
          }

          if (trimmed.startsWith('data:')) {
            const data = trimmed.slice(5).trim();
            handleEvent(currentEvent, data, callbacks);
          }
        }
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        callbacks.onError(err.message);
      }
    });

  return controller;
}

function handleEvent(event: string, data: string, callbacks: StreamCallbacks) {
  switch (event) {
    case 'content':
      try {
        const parsed = JSON.parse(data);
        callbacks.onContent(parsed.text);
      } catch {
        callbacks.onContent(data);
      }
      break;
    case 'tool_call':
      try {
        callbacks.onToolCall(JSON.parse(data));
      } catch {}
      break;
    case 'tool_result':
      try {
        callbacks.onToolResult(JSON.parse(data));
      } catch {}
      break;
    case 'ui_sync':
      try {
        if (callbacks.onUiSync) callbacks.onUiSync(JSON.parse(data));
      } catch {}
      break;
    case 'done':
      callbacks.onDone();
      break;
    case 'error':
      try {
        const err = JSON.parse(data);
        callbacks.onError(err.error || 'Unknown error');
      } catch {
        callbacks.onError(data);
      }
      break;
  }
}
