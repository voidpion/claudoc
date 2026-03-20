import { useState } from 'react';
import type { TreeNode } from '../../types';
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
    return (
      <div className="tree-folder">
        <div
          className="tree-item folder"
          style={{ paddingLeft: depth * 16 }}
          onClick={() => setExpanded(!expanded)}
        >
          <span className="tree-icon">{expanded ? '▼' : '▶'}</span>
          <span className={`tree-name ${isMemory ? 'memory-folder' : ''}`}>
            {node.name}
          </span>
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
      </div>
    </div>
  );
}
