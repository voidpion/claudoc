import type { TreeNode, Document, Conversation } from '../types';

const BASE = '/api';

export async function fetchTree(): Promise<TreeNode[]> {
  const res = await fetch(`${BASE}/notes/tree`);
  return res.json();
}

export async function fetchNote(id: string): Promise<Document> {
  const res = await fetch(`${BASE}/notes/${id}`);
  return res.json();
}

export async function createConversation(): Promise<Conversation> {
  const res = await fetch(`${BASE}/conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'New Chat' }),
  });
  return res.json();
}

export async function fetchConversations(): Promise<Conversation[]> {
  const res = await fetch(`${BASE}/conversations`);
  return res.json();
}
