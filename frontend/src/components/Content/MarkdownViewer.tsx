import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { Document } from '../../types';
import './MarkdownViewer.css';

interface Props {
  document: Document | null;
  loading: boolean;
}

export default function MarkdownViewer({ document, loading }: Props) {
  if (loading) {
    return (
      <div className="markdown-viewer">
        <div className="loading">Loading...</div>
      </div>
    );
  }

  if (!document) {
    return (
      <div className="markdown-viewer">
        <div className="welcome">
          <h1>Welcome to Claudoc</h1>
          <p>Select a note from the sidebar or chat with the AI assistant.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="markdown-viewer">
      <div className="document-header">
        <h1>{document.title}</h1>
        <span className="document-path">{document.path}</span>
      </div>
      <div className="document-content">
        <ReactMarkdown
          components={{
            code({ className, children, ...props }) {
              const match = /language-(\w+)/.exec(className || '');
              const inline = !match;
              return !inline ? (
                <SyntaxHighlighter
                  style={oneDark}
                  language={match[1]}
                  PreTag="div"
                >
                  {String(children).replace(/\n$/, '')}
                </SyntaxHighlighter>
              ) : (
                <code className={className} {...props}>
                  {children}
                </code>
              );
            },
          }}
        >
          {document.content}
        </ReactMarkdown>
      </div>
    </div>
  );
}
