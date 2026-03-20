export interface TreeNode {
  id: string;
  name: string;
  type: 'file' | 'folder';
  children: TreeNode[];
}

export interface Document {
  id: string;
  path: string;
  title: string;
  content: string;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string | null;
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'tool';
  content: string;
  toolName?: string;
  toolArgs?: string;
  toolResult?: string;
  isStreaming?: boolean;
}

export interface ToolCallEvent {
  id: string;
  name: string;
  arguments: string;
}

export interface ToolResultEvent {
  tool_call_id: string;
  name: string;
  result: string;
}
