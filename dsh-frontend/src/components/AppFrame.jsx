import { useEffect, useRef, useState, useCallback } from 'react';
import css from './AppFrame.module.css';

const SIDEBAR_DEFAULT = 248;
const SIDEBAR_MIN = 220;
const SIDEBAR_AUTO_COLLAPSE = 720;

/** 三栏外壳骨架（对齐原 Harness AppFrame）：sidebar | center | details。
 *  这里简化为 sidebar | center（details 列 Java 后端暂无数据，折叠为 0）。 */
export function AppFrame({ collapsed, onToggleSidebar, sidebar, sidebarChildren, children }) {
  const frameRef = useRef(null);
  const [viewport, setViewport] = useState(() => window.innerWidth);

  useEffect(() => {
    const el = frameRef.current;
    if (!el) return;
    let raf = null;
    const obs = new ResizeObserver(() => {
      raf ??= requestAnimationFrame(() => {
        raf = null;
        const w = el.getBoundingClientRect().width;
        if (w > 0) setViewport(w);
      });
    });
    obs.observe(el);
    return () => { obs.disconnect(); if (raf) cancelAnimationFrame(raf); };
  }, []);

  const narrow = viewport < SIDEBAR_AUTO_COLLAPSE;
  const sbCollapsed = collapsed ?? narrow;
  const sbWidth = sbCollapsed ? 56 : (sidebar ?? SIDEBAR_DEFAULT);

  return (
    <div
      ref={frameRef}
      className={css.frame}
      style={{ gridTemplateColumns: `${sbWidth}px minmax(0, 1fr) 0px` }}
      data-sidebar-collapsed={sbCollapsed || undefined}
      data-details-collapsed={undefined}
    >
      <div className={css.sidebarCol}>{sidebarChildren}</div>
      <div className={css.centerCol}>{children}</div>
      <div className={css.overlayLayer} />
    </div>
  );
}

export { SIDEBAR_DEFAULT, SIDEBAR_MIN, SIDEBAR_AUTO_COLLAPSE };
