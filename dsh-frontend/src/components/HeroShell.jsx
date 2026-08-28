import css from './HeroShell.module.css';
import { BrandMark } from './Icons';

/** 空会话欢迎页（对齐原 Harness HeroShell）：品牌标记 + 标题。输入框由 ConversationRoot 的 composer seat 提供。 */
export function HeroShell() {
  return (
    <div className={css.root}>
      <div className={css.stack}>
        <div className={css.headline}>
          <span className={css.fishHitbox}>
            <span className={css.fish}><BrandMark size={34} /></span>
          </span>
          <span className={css.headlineText}>DeepSeek Harness</span>
        </div>
      </div>
    </div>
  );
}

/** 蓝色光晕背景（对齐 ConversationRoot .heroGlow）。 */
export function HeroGlow({ className }) {
  return (
    <svg className={className} viewBox="0 0 1051 468" preserveAspectRatio="xMidYMid meet" aria-hidden>
      <defs>
        <radialGradient id="dsh-hero-glow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="var(--dsw-static-deepseek-200)" stopOpacity="0.55" />
          <stop offset="60%" stopColor="var(--dsw-static-deepseek-200)" stopOpacity="0.12" />
          <stop offset="100%" stopColor="var(--dsw-static-deepseek-200)" stopOpacity="0" />
        </radialGradient>
      </defs>
      <ellipse cx="525.5" cy="234" rx="525.5" ry="234" fill="url(#dsh-hero-glow)" />
    </svg>
  );
}
