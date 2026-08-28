import { memo, useState, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import mdCss from './MarkdownText.module.css';
import codeCss from './CodeBlock.module.css';

function CodeBlock({ className, children }) {
  const [copied, setCopied] = useState(false);
  const lang = /language-(\w+)/.exec(className || '')?.[1] || 'text';
  const code = String(children ?? '');
  const onCopy = useCallback(() => {
    navigator.clipboard?.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }, [code]);
  return (
    <div className={codeCss.block}>
      <div className={codeCss.bannerWrap}>
        <div className={codeCss.banner}>
          <span className={codeCss.infostring}>{lang}</span>
          <span className={codeCss.action}>
            <button type="button" className={codeCss.copyButton} onClick={onCopy}>
              {copied ? '已复制' : '复制'}
            </button>
          </span>
        </div>
      </div>
      <pre>
        <code className={className}>{code}</code>
      </pre>
    </div>
  );
}

export const Markdown = memo(function Markdown({ children }) {
  return (
    <div className={mdCss.markdown}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code: ({ inline, className, children, ...props }) =>
            inline ? (
              <code className={className} {...props}>{children}</code>
            ) : (
              <CodeBlock className={className}>{children}</CodeBlock>
            ),
          // GFM tables: wrap in a scroll container with the wide-table hook
          table: ({ children }) => (
            <div className={`${mdCss.tableScroll} ${mdCss.tableFill}`}>
              <table>{children}</table>
            </div>
          ),
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
});
