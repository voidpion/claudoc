import { useState, useEffect, useCallback } from 'react';
import FileTree from './components/Sidebar/FileTree';
import MarkdownViewer from './components/Content/MarkdownViewer';
import ChatPanel from './components/Chat/ChatPanel';
import type { TreeNode, Document } from './types';
import { fetchTree, fetchNote } from './services/api';
import './App.css';

export default function App() {
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [document, setDocument] = useState<Document | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);

  const loadTree = useCallback(async () => {
    try {
      const data = await fetchTree();
      setTree(data);
    } catch (err) {
      console.error('Failed to load tree:', err);
    }
  }, []);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  const handleSelectNote = async (id: string) => {
    setSelectedId(id);
    setDocLoading(true);
    try {
      const doc = await fetchNote(id);
      setDocument(doc);
    } catch (err) {
      console.error('Failed to load note:', err);
      setDocument(null);
    }
    setDocLoading(false);
  };

  return (
    <div className="app">
      <div className="sidebar">
        <FileTree
          tree={tree}
          selectedId={selectedId}
          onSelect={handleSelectNote}
          onRefresh={loadTree}
        />
      </div>
      <div className="content">
        <MarkdownViewer document={document} loading={docLoading} />
      </div>
      <div className="chat">
        <ChatPanel
          conversationId={conversationId}
          onConversationCreated={setConversationId}
          onNoteChanged={loadTree}
        />
      </div>
    </div>
  );
}
