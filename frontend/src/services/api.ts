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

// Trash APIs
export async function fetchTrash(): Promise<Document[]> {
  const res = await fetch(`${BASE}/trash`);
  return res.json();
}

export async function restoreNote(id: string): Promise<Document> {
  const res = await fetch(`${BASE}/trash/${id}/restore`, { method: 'POST' });
  return res.json();
}

export async function permanentDeleteNote(id: string): Promise<void> {
  await fetch(`${BASE}/trash/${id}`, { method: 'DELETE' });
}

// Upload
export async function createNote(path: string, title: string, content: string): Promise<Document> {
  const res = await fetch(`${BASE}/notes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, title, content }),
  });
  return res.json();
}
