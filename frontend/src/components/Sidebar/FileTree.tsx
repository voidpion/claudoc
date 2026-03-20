import { useState, useEffect } from 'react';
import type { TreeNode, Document } from '../../types';
import { fetchTrash, restoreNote, permanentDeleteNote } from '../../services/api';
import './FileTree.css';

interface Props {
  tree: TreeNode[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onRefresh: () => void;
}

function TreeItem({
  node,
  selectedId,
  onSelect,
  depth,
}: {
  node: TreeNode;
  selectedId: string | null;
  onSelect: (id: string) => void;
  depth: number;
}) {
  const [expanded, setExpanded] = useState(true);
  const isMemory = node.name === '_memory';

  if (node.type === 'folder') {
    const folderIcon = isMemory ? '🧠' : (expanded ? '📂' : '📁');
    return (
      <div className="tree-folder">
        <div
          className={`tree-item folder ${isMemory ? 'memory-folder' : ''}`}
          style={{ paddingLeft: depth * 16 }}
          onClick={() => setExpanded(!expanded)}
        >
          <span className="tree-icon">{folderIcon}</span>
          <span className="tree-name">{node.name}</span>
        </div>
        {expanded && (
          <div className="tree-children">
            {node.children.map((child) => (
              <TreeItem
                key={child.id}
                node={child}
                selectedId={selectedId}
                onSelect={onSelect}
                depth={depth + 1}
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <div
      className={`tree-item file ${selectedId === node.id ? 'selected' : ''}`}
      style={{ paddingLeft: depth * 16 }}
      onClick={() => onSelect(node.id)}
    >
      <span className="tree-icon">📄</span>
      <span className="tree-name">{node.name}</span>
    </div>
  );
}

export default function FileTree({ tree, selectedId, onSelect, onRefresh }: Props) {
  const [trashExpanded, setTrashExpanded] = useState(false);
  const [trashItems, setTrashItems] = useState<Document[]>([]);
  const [trashLoading, setTrashLoading] = useState(false);

  const loadTrash = async () => {
    setTrashLoading(true);
    try {
      const items = await fetchTrash();
      setTrashItems(items);
    } catch (err) {
      console.error('Failed to load trash:', err);
    }
    setTrashLoading(false);
  };

  useEffect(() => {
    if (trashExpanded) {
      loadTrash();
    }
  }, [trashExpanded]);

  const handleRestore = async (id: string) => {
    try {
      await restoreNote(id);
      await loadTrash();
      onRefresh();
    } catch (err) {
      console.error('Failed to restore:', err);
    }
  };

  const handlePermanentDelete = async (id: string) => {
    if (!confirm('Permanently delete this note? This cannot be undone.')) return;
    try {
      await permanentDeleteNote(id);
      await loadTrash();
    } catch (err) {
      console.error('Failed to delete:', err);
    }
  };

  return (
    <div className="file-tree">
      <div className="file-tree-header">
        <span>Knowledge Base</span>
        <button className="refresh-btn" onClick={onRefresh} title="Refresh">
          ↻
        </button>
      </div>
      <div className="file-tree-content">
        {tree.map((node) => (
          <TreeItem
            key={node.id}
            node={node}
            selectedId={selectedId}
            onSelect={onSelect}
            depth={1}
          />
        ))}
        <div className="trash-section">
        <div
          className="tree-item trash-header"
          onClick={() => setTrashExpanded(!trashExpanded)}
        >
          <span className="tree-icon">🗑️</span>
          <span className="tree-name">Trash</span>
          {trashItems.length > 0 && (
            <span className="trash-count">{trashItems.length}</span>
          )}
          <span className="tool-toggle">{trashExpanded ? '▼' : '▶'}</span>
        </div>
        {trashExpanded && (
          <div className="trash-content">
            {trashLoading && <div className="trash-loading">Loading...</div>}
            {!trashLoading && trashItems.length === 0 && (
              <div className="trash-empty">Empty</div>
            )}
            {trashItems.map((item) => (
              <div key={item.id} className="trash-item">
                <span className="tree-icon">📄</span>
                <span className="tree-name">{item.title}</span>
                <div className="trash-actions">
                  <button
                    className="trash-action restore"
                    onClick={() => handleRestore(item.id)}
                    title="Restore"
                  >
                    ↩
                  </button>
                  <button
                    className="trash-action delete"
                    onClick={() => handlePermanentDelete(item.id)}
                    title="Delete permanently"
                  >
                    ✕
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
        </div>
      </div>
    </div>
  );
}
