// 内联 SVG 图标 —— 替代无法直接使用的 @deepseek-ai/dsh-client-ui-primitives 图标包。
// 视觉对齐原 Harness 的 16px 描边图标风格。

const base = { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none', 'aria-hidden': true };

export function IconPlus({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <path d="M8 3.5v9M3.5 8h9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}

export function IconSend({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <path d="M8.3125 0.98C8.668 1.053 8.979 1.204 9.263 1.432C9.487 1.613 9.73 1.858 9.979 2.107L14.707 6.835L13.293 8.249L9 3.956V15.042H7V3.956L2.707 8.249L1.293 6.835L6.02 2.107C6.27 1.858 6.513 1.613 6.737 1.432C6.977 1.24 7.284 1.044 7.688 0.98C7.897 0.947 8.103 0.955 8.3125 0.98Z" fill="currentColor" />
    </svg>
  );
}

export function IconStop({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <rect x="3" y="3" width="10" height="10" rx="3" fill="currentColor" />
    </svg>
  );
}

export function IconFolder({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <path d="M2 4.5C2 3.67 2.67 3 3.5 3h3l1.5 1.5h4.5c.83 0 1.5.67 1.5 1.5v6c0 .83-.67 1.5-1.5 1.5h-9C2.67 13.5 2 12.83 2 12V4.5Z" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}

export function IconChevron({ size = 12, ...p }) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" fill="none" aria-hidden {...p}>
      <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function IconSettings({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <circle cx="8" cy="8" r="2.2" stroke="currentColor" strokeWidth="1.3" />
      <path d="M8 1.5v1.6M8 12.9v1.6M14.5 8h-1.6M3.1 8H1.5M12.6 3.4l-1.1 1.1M4.5 11.5l-1.1 1.1M12.6 12.6l-1.1-1.1M4.5 4.5L3.4 3.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}

export function IconSun({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <circle cx="8" cy="8" r="3" stroke="currentColor" strokeWidth="1.3" />
      <path d="M8 1.5v2M8 12.5v2M14.5 8h-2M3.5 8h-2M12.6 3.4l-1.4 1.4M4.8 11.2l-1.4 1.4M12.6 12.6l-1.4-1.4M4.8 4.8L3.4 3.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}

export function IconMoon({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <path d="M13 8.5A5.5 5.5 0 017.5 3a5 5 0 105.5 5.5Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
    </svg>
  );
}

export function IconPanel({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <rect x="2" y="3" width="12" height="10" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
      <path d="M6.5 3v10" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}

export function IconClose({ size = 16, ...p }) {
  return (
    <svg {...base} width={size} height={size} {...p}>
      <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}

// 品牌标记：鲸鱼剪影（对齐原 Harness 的 fish/whale hero mark）
export function BrandMark({ size = 34, ...p }) {
  return (
    <svg width={size} height={size} viewBox="0 0 34 34" fill="none" aria-hidden {...p}>
      <path d="M5 19c0-5 4-9 10-9 4 0 7 2 9 5l4-1.5-2 4.5c0 4-3 8-8 8-3 0-5-1-6-2l-3 2 1-3.5C7 21 5 20 5 19Z" fill="currentColor" />
      <circle cx="11" cy="17" r="1.4" fill="var(--dsw-alias-bg-base)" />
    </svg>
  );
}
