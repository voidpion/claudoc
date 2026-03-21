import { useState, useEffect, useCallback, useRef } from 'react';
import FileTree from './components/Sidebar/FileTree';
import MarkdownViewer from './components/Content/MarkdownViewer';
import ChatPanel from './components/Chat/ChatPanel';
import type { TreeNode, Document } from './types';
import type { UiSyncEvent } from './services/sse';
import { fetchTree, fetchNote } from './services/api';
import './App.css';

export default function App() {
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [document, setDocument] = useState<Document | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [trashVersion, setTrashVersion] = useState(0);

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

  const handleUiSync = useCallback((sync: UiSyncEvent) => {
    const targets = sync.refresh;
    if (targets.includes('tree')) {
      loadTree();
    }
    if (targets.includes('document') && sync.documentId && sync.documentId === selectedId) {
      // Reload the currently viewed document
      fetchNote(sync.documentId).then(setDocument).catch(() => {});
    }
    if (targets.includes('trash')) {
      setTrashVersion((v) => v + 1);
    }
  }, [loadTree, selectedId]);

  return (
    <div className="app">
      <div className="sidebar">
        <FileTree
          tree={tree}
          selectedId={selectedId}
          onSelect={handleSelectNote}
          onRefresh={loadTree}
          trashVersion={trashVersion}
        />
      </div>
      <div className="content">
        <MarkdownViewer document={document} loading={docLoading} />
      </div>
      <div className="chat">
        <ChatPanel
          conversationId={conversationId}
          onConversationCreated={setConversationId}
          onUiSync={handleUiSync}
        />
      </div>
    </div>
  );
}
