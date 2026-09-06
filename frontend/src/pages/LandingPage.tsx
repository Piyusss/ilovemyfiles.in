import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAuth, UserButton } from '@clerk/clerk-react';
import { motion } from 'framer-motion';
import {
  Cloud, ArrowRight, UploadCloud, History, ShieldCheck,
  Boxes, Database, Server, HardDrive, ChevronDown,
  FileText, Folder, Image,
} from 'lucide-react';

/* Lucide ships generic UI icons only (no brand logos), so the footer's
   LinkedIn/GitHub/YouTube marks are inlined here as plain SVG paths. */
function SocialIcon({ path, label }: { path: string; label: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className="w-4 h-4"
      fill="currentColor"
      aria-label={label}
    >
      <path d={path} />
    </svg>
  );
}

const SOCIAL_ICON_PATHS = {
  linkedin:
    'M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433c-1.144 0-2.063-.926-2.063-2.065 0-1.138.92-2.063 2.063-2.063 1.14 0 2.064.925 2.064 2.063 0 1.139-.925 2.065-2.064 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z',
  github:
    'M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12',
  youtube:
    'M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z',
};

/* ─────────────────────────────────────────────────────────────────────────────
   Motion primitives — dependency-free, respect prefers-reduced-motion
   ───────────────────────────────────────────────────────────────────────────── */

const prefersReduced =
  typeof window !== 'undefined' &&
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

function useInView<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === 'undefined') {
      setInView(true);
      return;
    }
    const obs = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          obs.unobserve(el);
        }
      },
      { threshold: 0.18, rootMargin: '0px 0px -8% 0px' }
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, []);
  return { ref, inView };
}

function Reveal({
  children,
  delay = 0,
  y = 28,
  className = '',
}: {
  children: ReactNode;
  delay?: number;
  y?: number;
  className?: string;
}) {
  const { ref, inView } = useInView<HTMLDivElement>();
  const show = inView || prefersReduced;
  return (
    <div
      ref={ref}
      className={className}
      style={{
        opacity: show ? 1 : 0,
        transform: show ? 'none' : `translateY(${y}px)`,
        transition: prefersReduced
          ? 'none'
          : `opacity 0.7s ease ${delay}ms, transform 0.85s cubic-bezier(0.16,1,0.3,1) ${delay}ms`,
        willChange: 'opacity, transform',
      }}
    >
      {children}
    </div>
  );
}

// Curved dotted connector that follows the alternating (zig-zag) card layout.
// `dir='rl'` sweeps top-right → bottom-left, `dir='lr'` sweeps top-left → bottom-right.
// It "draws" downward on scroll via a clip-path wipe; the arrowhead lands on the end side.
function Connector({ dir }: { dir: 'lr' | 'rl' }) {
  const { ref, inView } = useInView<HTMLDivElement>();
  const show = inView || prefersReduced;

  // Cubic bezier across a 0..100 box; preserveAspectRatio="none" stretches it
  // to the full container width, and non-scaling-stroke keeps the dots crisp.
  const path =
    dir === 'rl'
      ? 'M 76 6 C 76 48, 24 52, 24 94'
      : 'M 24 6 C 24 48, 76 52, 76 94';
  const endLeft = dir === 'rl' ? '24%' : '76%';

  return (
    <div ref={ref} aria-hidden className="relative w-full h-28 sm:h-36 my-1">
      <svg
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        className="w-full h-full overflow-visible"
        style={{
          clipPath: show ? 'inset(-10% 0 0 0)' : 'inset(-10% 0 100% 0)',
          transition: prefersReduced ? 'none' : 'clip-path 0.9s cubic-bezier(0.16,1,0.3,1)',
        }}
      >
        <path
          d={path}
          className="text-black"
          fill="none"
          stroke="currentColor"
          strokeWidth={1.5}
          strokeDasharray="1 7"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
      <div
        className="absolute bottom-0"
        style={{
          left: endLeft,
          transform: 'translateX(-50%)',
          opacity: show ? 1 : 0,
          transition: prefersReduced ? 'none' : 'opacity 0.45s ease 0.65s',
        }}
      >
        <ChevronDown className="w-5 h-5 text-black" />
      </div>
    </div>
  );
}

// Subtle parallax for the decorative hero grid
function useParallax<T extends HTMLElement>(speed = -0.18) {
  const ref = useRef<T | null>(null);
  useEffect(() => {
    if (prefersReduced) return;
    let raf = 0;
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        if (ref.current) {
          ref.current.style.transform = `translate3d(0, ${window.scrollY * speed}px, 0)`;
        }
      });
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    return () => {
      window.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(raf);
    };
  }, [speed]);
  return ref;
}

/* ─────────────────────────────────────────────────────────────────────────────
   Monochrome building blocks
   ───────────────────────────────────────────────────────────────────────────── */

function CodeBlock({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-lg border border-surface-800 bg-surface-950 overflow-hidden">
      <div className="flex items-center gap-1.5 px-4 py-2.5 border-b border-surface-800 bg-surface-900/50">
        <span className="w-2.5 h-2.5 rounded-full bg-surface-700" />
        <span className="w-2.5 h-2.5 rounded-full bg-surface-700" />
        <span className="w-2.5 h-2.5 rounded-full bg-surface-700" />
        <span className="ml-2 text-xs text-surface-500 font-mono">{title}</span>
      </div>
      <pre className="px-4 py-4 text-xs sm:text-[13px] leading-[1.7] font-mono text-surface-300 overflow-x-auto">
        {children}
      </pre>
    </div>
  );
}

const dim = 'text-surface-600';
const hi = 'text-white';

/* ── Per-feature visuals ──────────────────────────────────────────────────── */

function ChunkVisual() {
  return (
    <div className="rounded-lg border border-surface-800 bg-surface-900/40 p-5">
      <div className="grid grid-cols-8 gap-1.5">
        {Array.from({ length: 32 }).map((_, i) => (
          <div
            key={i}
            className="aspect-square rounded-sm"
            style={{ background: i < 22 ? 'rgb(var(--surface-200))' : 'rgb(var(--surface-800))' }}
          />
        ))}
      </div>
      <div className="flex items-center justify-between mt-4 text-xs font-mono">
        <span className="text-surface-400">chunk 22 / 32</span>
        <span className="text-surface-600">resuming · 3 parallel</span>
      </div>
    </div>
  );
}

function DedupVisual() {
  return (
    <CodeBlock title="dedup.sql">
      <div><span className={dim}>--</span> <span className={dim}>before any upload</span></div>
      <div><span className={hi}>SELECT</span> id, ref_count</div>
      <div><span className={hi}>FROM</span> physical_files</div>
      <div><span className={hi}>WHERE</span> hash = <span className="text-surface-200">'a1b2c3…'</span>;</div>
      <div className="h-3" />
      <div><span className={dim}>-- hit → skip transfer, ref_count++</span></div>
      <div className="text-surface-200">ref_count: 1 → 2</div>
    </CodeBlock>
  );
}

function VersionVisual() {
  const versions = [
    { v: 'v3', label: 'current', size: '2.4 MB', when: 'just now' },
    { v: 'v2', label: '', size: '2.1 MB', when: '3 days ago' },
    { v: 'v1', label: '', size: '1.8 MB', when: 'last week' },
  ];
  return (
    <div className="rounded-lg border border-surface-800 bg-surface-900/40 p-5 space-y-2.5">
      {versions.map((r, i) => (
        <div
          key={r.v}
          className="flex items-center gap-3 px-3 py-2.5 rounded-md border border-surface-800 bg-surface-950"
        >
          <span
            className="w-1.5 h-1.5 rounded-full"
            style={{ background: i === 0 ? 'rgb(var(--c-white))' : 'rgb(var(--surface-600))' }}
          />
          <span className="font-mono text-sm text-surface-200 w-8">{r.v}</span>
          {r.label && (
            <span className="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded border border-surface-700 text-surface-300">
              {r.label}
            </span>
          )}
          <span className="ml-auto text-xs text-surface-500 font-mono">{r.size}</span>
          <span className="text-xs text-surface-600 hidden sm:block w-20 text-right">{r.when}</span>
        </div>
      ))}
    </div>
  );
}

function SecurityVisual() {
  return (
    <CodeBlock title="SecurityConfig.java">
      <div><span className={dim}>// every request verified against Clerk JWKS</span></div>
      <div>http.<span className="text-surface-200">oauth2ResourceServer</span>(o {'->'} o</div>
      <div>{'  '}.<span className="text-surface-200">jwt</span>(jwt {'->'} jwt</div>
      <div>{'    '}.jwtAuthenticationConverter(<span className={hi}>clerk</span>)));</div>
      <div className="h-3" />
      <div><span className={dim}>// per-user isolation at the query layer</span></div>
      <div><span className="text-surface-200">findByIdAndUserId</span>(fileId, userId)</div>
    </CodeBlock>
  );
}

function RedisVisual() {
  return (
    <div className="space-y-3">
      {/* redis-cli terminal — the app's real cache keys */}
      <CodeBlock title="redis-cli · Upstash">
        <div><span className={dim}>$</span> GET ilovemyfiles:files::u42:root</div>
        <div className="text-white">  ↳ HIT · 8 ms</div>
        <div className="h-2.5" />
        <div><span className={dim}>$</span> KEYS ilovemyfiles:*</div>
        <div className="text-surface-400">  1) ilovemyfiles:user-dto::u42</div>
        <div className="text-surface-400">  2) ilovemyfiles:files::u42:root</div>
        <div className="text-surface-400">  3) ilovemyfiles:folders::u42:root</div>
        <div className="h-2.5" />
        <div><span className={dim}># on upload / delete / move</span></div>
        <div><span className="text-white">@CacheEvict</span> <span className={dim}>→ keys invalidated</span></div>
      </CodeBlock>

      {/* Redis HIT vs DB MISS */}
      <div className="rounded-lg border border-surface-800 bg-surface-900/40 p-4 space-y-3">
        <div className="flex items-center gap-3">
          <span className="text-xs font-mono text-surface-200 w-20 shrink-0">Redis HIT</span>
          <div className="flex-1 h-1.5 rounded-full bg-surface-800 overflow-hidden">
            <div className="h-full rounded-full bg-white" style={{ width: '8%' }} />
          </div>
          <span className="text-xs font-mono text-surface-300 w-12 text-right">8 ms</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs font-mono text-surface-500 w-20 shrink-0">DB miss</span>
          <div className="flex-1 h-1.5 rounded-full bg-surface-800 overflow-hidden">
            <div className="h-full rounded-full" style={{ width: '92%', background: 'rgb(var(--surface-600))' }} />
          </div>
          <span className="text-xs font-mono text-surface-500 w-12 text-right">180 ms</span>
        </div>
      </div>
    </div>
  );
}

function ArchVisual() {
  const Box = ({ icon: Icon, label }: { icon: typeof Database; label: string }) => (
    <div className="flex items-center gap-2 px-3 py-2 rounded-md border border-surface-800 bg-surface-950">
      <Icon className="w-4 h-4 text-surface-400 shrink-0" />
      <span className="text-xs text-surface-300 font-mono">{label}</span>
    </div>
  );
  return (
    <div className="rounded-lg border border-surface-800 bg-surface-900/40 p-5">
      <div className="flex flex-col items-center gap-3">
        <div className="px-3 py-2 rounded-md border border-surface-700 bg-surface-900 text-xs font-mono text-surface-200">
          React 19 · TypeScript
        </div>
        <div className="w-px h-4 bg-surface-700" />
        <div className="px-3 py-2 rounded-md border border-surface-600 bg-surface-900 text-xs font-mono text-white">
          Spring Boot API · Java 21
        </div>
        <div className="w-px h-4 bg-surface-700" />
        <div className="grid grid-cols-3 gap-2 w-full">
          <Box icon={Database} label="Neon" />
          <Box icon={Server} label="Upstash" />
          <Box icon={HardDrive} label="B2" />
        </div>
        <p className="text-xs text-surface-600 pt-1">Swap any provider via env vars, no code changes.</p>
      </div>
    </div>
  );
}

/* ── Content ──────────────────────────────────────────────────────────────── */

const highlights: {
  icon: typeof UploadCloud;
  kicker: string;
  title: ReactNode;
  shortDesc: string;
  desc: string;
  visual: ReactNode;
  /** Icon-badge border/background/icon color — echoes the hero's blob hues. */
  color: string;
}[] = [
  {
    icon: UploadCloud,
    kicker: 'Transfers',
    title: 'Resumable chunked uploads',
    shortDesc: '5 MB chunks, 3 in parallel. Drops mid-upload? Resumes from the last completed chunk.',
    desc: 'Files are split into 5 MB chunks and uploaded three at a time, each with its own retry and exponential backoff. Lose your connection at 99%? The transfer resumes from the last completed chunk - never from zero.',
    visual: <ChunkVisual />,
    color: 'border-blue-100 bg-blue-50 text-blue-500',
  },
  {
    icon: Boxes,
    kicker: 'Storage',
    title: 'Content-addressed deduplication',
    shortDesc: 'SHA-256 hashed before upload. Identical content stored once, shared by reference.',
    desc: 'Every file is hashed with SHA-256 before it leaves the browser. If that exact content already exists, the upload is skipped entirely and the object is shared via a reference count - a thousand identical files cost one.',
    visual: <DedupVisual />,
    color: 'border-violet-100 bg-violet-50 text-violet-500',
  },
  {
    icon: History,
    kicker: 'History',
    title: 'Versioning built in',
    shortDesc: 'Overwriting creates a version. Restore any previous state in one click.',
    desc: 'Overwriting a file never destroys the old one - it becomes a version. Restore any previous state with a click, while storage accounting tracks only the delta between versions.',
    visual: <VersionVisual />,
    color: 'border-amber-100 bg-amber-50 text-amber-500',
  },
  {
    icon: ShieldCheck,
    kicker: 'Security',
    title: 'Authenticated by default',
    shortDesc: 'Clerk JWTs verified on every request. Data isolated per user, share links support passwords.',
    desc: 'Sessions are issued by Clerk; the backend acts as an OAuth2 resource server, verifying every request against Clerk\'s rotating public keys. Data is isolated per user at the query layer, and share links support passwords and expiry.',
    visual: <SecurityVisual />,
    color: 'border-emerald-100 bg-emerald-50 text-emerald-500',
  },
  {
    icon: Database,
    kicker: 'Redis · Upstash',
    title: <><span style={{ color: '#DC382C' }}>Redis</span> cached for instant reads</>,
    shortDesc: 'File lists and folders served from Redis. Single-digit ms reads, explicit invalidation on every write.',
    desc: 'Every hot path - file lists, folders, user metadata - is served from Redis. The backend uses a cache-aside pattern with explicit @CacheEvict invalidation on every upload, delete, and move, so reads land in single-digit milliseconds and never go stale. Search runs on Postgres full-text indexes.',
    visual: <RedisVisual />,
    color: 'border-rose-100 bg-rose-50 text-rose-500',
  },
  {
    icon: Boxes,
    kicker: 'Architecture',
    title: 'Cloud-native & pluggable',
    shortDesc: 'Spring Boot + Postgres + B2 + Redis. Swap any provider via env vars.',
    desc: 'Stateless Spring Boot services backed by Neon Postgres, Backblaze B2 object storage, and Upstash Redis. Every dependency is S3 or standards-compatible, so providers swap with an environment variable.',
    visual: <ArchVisual />,
    color: 'border-cyan-100 bg-cyan-50 text-cyan-500',
  },
];

/* ── Hero illustration — floating files converging on a cloud ──────────────── */

const orbitIcons: {
  Icon: typeof Cloud;
  color: string;
  top: string;
  left: string;
  box: string;
  iconSize: string;
  delay: number;
  duration: number;
}[] = [
  { Icon: Cloud,      color: 'bg-blue-100 text-blue-500',       top: '36%', left: '40%', box: 'w-20 h-20', iconSize: 'w-9 h-9', delay: 0,   duration: 4.2 },
  { Icon: FileText,   color: 'bg-violet-100 text-violet-500',   top: '4%',  left: '6%',  box: 'w-14 h-14', iconSize: 'w-6 h-6', delay: 0.3, duration: 3.6 },
  { Icon: Folder,     color: 'bg-amber-100 text-amber-500',     top: '8%',  left: '72%', box: 'w-14 h-14', iconSize: 'w-6 h-6', delay: 0.6, duration: 3.9 },
  { Icon: Image,      color: 'bg-emerald-100 text-emerald-500', top: '66%', left: '4%',  box: 'w-14 h-14', iconSize: 'w-6 h-6', delay: 0.9, duration: 3.4 },
  { Icon: ShieldCheck,color: 'bg-rose-100 text-rose-500',       top: '68%', left: '76%', box: 'w-14 h-14', iconSize: 'w-6 h-6', delay: 0.2, duration: 4.4 },
];

function StorageIllustration() {
  return (
    <div className="relative w-full h-[22rem]" aria-hidden>
      {orbitIcons.map(({ Icon, color, top, left, box, iconSize, delay, duration }, i) => (
        <motion.div
          key={i}
          className={`absolute ${box} rounded-2xl shadow-sm flex items-center justify-center ${color}`}
          style={{ top, left }}
          initial={{ opacity: 0, scale: 0.6, y: 16 }}
          animate={{ opacity: 1, scale: 1, y: [0, -14, 0] }}
          transition={{
            opacity: { duration: 0.5, delay },
            scale: { duration: 0.5, delay },
            y: { duration, repeat: Infinity, ease: 'easeInOut', delay: delay + 0.5 },
          }}
        >
          <Icon className={iconSize} />
        </motion.div>
      ))}
    </div>
  );
}

/* ─────────────────────────────────────────────────────────────────────────────
   Page
   ───────────────────────────────────────────────────────────────────────────── */

export function LandingPage() {
  // No redirect — logged-in users can browse the landing page freely.
  const { isSignedIn, isLoaded } = useAuth();
  const gridRef = useParallax<HTMLDivElement>(-0.18);

  return (
    <div className="min-h-screen bg-surface-950 text-surface-100">

      {/* ── Nav ──────────────────────────────────────────────────────────── */}
      <nav className="border-b border-surface-800 sticky top-0 bg-surface-950/85 backdrop-blur-md z-30">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 bg-white rounded flex items-center justify-center">
              <Cloud className="w-4 h-4 text-surface-950" />
            </div>
            <span className="font-semibold text-white">ilovemyfiles.in</span>
          </div>
          <div className="flex items-center gap-1.5 sm:gap-3">
            {!isLoaded ? null : isSignedIn ? (
              <>
                <Link to="/files" className="btn-primary text-sm px-3 sm:px-4 py-1.5 flex items-center gap-1.5">
                  <span className="hidden xs:inline">Open app</span>
                  <span className="xs:hidden">App</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </Link>
                <UserButton appearance={{ elements: { avatarBox: 'w-7 h-7' } }} />
              </>
            ) : (
              <>
                <Link to="/sign-in" className="hidden sm:inline-flex text-sm text-surface-400 hover:text-white transition-colors px-3 py-1.5">
                  Sign in
                </Link>
                <Link to="/sign-up" className="btn-primary text-sm px-3 sm:px-4 py-1.5">
                  Get started
                </Link>
              </>
            )}
          </div>
        </div>
      </nav>

      {/* ── Hero ─────────────────────────────────────────────────────────── */}
      <section className="relative overflow-hidden">
        {/* Soft colorful ambient blobs — the bit of color the rest of the
            page borrows from (feature icon badges reuse these same hues). */}
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div className="absolute -top-28 -right-16 w-[26rem] h-[26rem] rounded-full bg-blue-200/50 blur-3xl" />
          <div className="absolute top-16 right-1/3 w-72 h-72 rounded-full bg-violet-200/40 blur-3xl" />
          <div className="absolute -top-10 right-10 w-64 h-64 rounded-full bg-rose-200/40 blur-3xl" />
        </div>
        <div
          ref={gridRef}
          aria-hidden
          className="pointer-events-none absolute inset-0 opacity-[0.18]"
          style={{
            backgroundImage: 'radial-gradient(circle at 1px 1px, rgb(var(--surface-600)) 1px, transparent 0)',
            backgroundSize: '34px 34px',
            maskImage: 'radial-gradient(ellipse 70% 60% at 30% 30%, #000 40%, transparent 100%)',
            WebkitMaskImage: 'radial-gradient(ellipse 70% 60% at 30% 30%, #000 40%, transparent 100%)',
          }}
        />
        <div className="relative max-w-5xl mx-auto px-4 sm:px-6 pt-16 sm:pt-24 pb-16 sm:pb-20 lg:grid lg:grid-cols-[1.15fr_0.85fr] lg:gap-8 lg:items-center">
          <div className="max-w-2xl">
            <Reveal>
              <h1 className="text-3xl sm:text-5xl lg:text-6xl font-bold text-white leading-[1.05] tracking-tight mb-5">
                Your <span className="text-blue-600">files</span>.<br />
                <span className="text-surface-400">Stored right.</span>
              </h1>
            </Reveal>
            <Reveal delay={80}>
              <p className="text-surface-400 text-xs sm:text-sm max-w-md mb-2 leading-relaxed">
                <span className="sm:hidden">Uploads, versioning, dedup, and sharing - done right.</span>
                <span className="hidden sm:inline">A cloud storage platform built like a real product: chunked resumable uploads, content-addressed deduplication, versioning, and secure sharing.</span>
              </p>
            </Reveal>
            <Reveal delay={160}>
              <div className="inline-flex items-center px-3 py-1 border border-surface-800 bg-surface-900/60 text-surface-400 text-xs font-medium mb-9">
                <span className="sm:hidden">Educational project</span>
                <span className="hidden sm:inline">Note: This Project is made for educational purposes by Piyush Raj</span>
              </div>
            </Reveal>
            <Reveal delay={240}>
              <div className="flex flex-wrap items-center gap-3">
                {!isLoaded ? null : isSignedIn ? (
                  <Link to="/files" className="btn-primary flex items-center gap-2 px-5 py-2.5">
                    Go to your files <ArrowRight className="w-4 h-4" />
                  </Link>
                ) : (
                  <>
                    <Link to="/sign-up" className="btn-primary flex items-center gap-2 px-5 py-2.5">
                      Start for free <ArrowRight className="w-4 h-4" />
                    </Link>
                    <Link
                      to="/sign-in"
                      className="px-5 py-2.5 rounded-md border border-surface-700 text-surface-300 hover:text-white hover:border-surface-500 transition-colors text-sm font-medium"
                    >
                      Sign in
                    </Link>
                  </>
                )}
              </div>
            </Reveal>
          </div>
          <div className="hidden lg:block">
            <StorageIllustration />
          </div>
        </div>
      </section>

      {/* ── Engineering highlights (scroll-reveal) ───────────────────────── */}
      <section className="bg-blue-50/70">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 pt-8 pb-10 sm:pt-12 sm:pb-16">
        <Reveal>
          <p className="text-center text-xs font-light uppercase tracking-[0.3em] text-surface-500 mb-12 sm:mb-16">
            Features
          </p>
        </Reveal>
        <div>
          {highlights.map((h, i) => {
            const flip = i % 2 === 1;
            return (
              <div key={h.kicker}>
                <div className="grid lg:grid-cols-2 gap-8 lg:gap-16 items-center">
                  <Reveal className={flip ? 'lg:order-2' : ''}>
                    <div>
                      <div className="inline-flex items-center gap-2 mb-4">
                        <span className={`w-8 h-8 rounded-md border flex items-center justify-center ${h.color}`}>
                          <h.icon className="w-4 h-4" />
                        </span>
                        <span className="text-xs font-medium text-surface-500 uppercase tracking-[0.18em]">
                          {h.kicker}
                        </span>
                      </div>
                      <h3 className="text-xl sm:text-2xl font-semibold text-white mb-3 tracking-tight">
                        {h.title}
                      </h3>
                      <p className="text-surface-400 leading-relaxed max-w-md">
                        <span className="sm:hidden">{h.shortDesc}</span>
                        <span className="hidden sm:inline">{h.desc}</span>
                      </p>
                    </div>
                  </Reveal>
                  <Reveal delay={140} y={36} className={flip ? 'lg:order-1' : ''}>
                    {h.visual}
                  </Reveal>
                </div>
                {i < highlights.length - 1 && (
                  <Connector dir={i % 2 === 0 ? 'rl' : 'lr'} />
                )}
              </div>
            );
          })}
        </div>
        </div>
      </section>

      {/* ── Product preview ──────────────────────────────────────────────── */}
      <section className="bg-violet-50/70">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-16 sm:py-24">
        <Reveal>
          <div className="text-center mb-12">
            <p className="text-xs font-medium text-surface-500 uppercase tracking-[0.2em] mb-3">
              The interface
            </p>
            <h2 className="text-2xl sm:text-3xl font-bold text-white tracking-tight">
              Clean, fast, out of your way
            </h2>
          </div>
        </Reveal>
        <Reveal delay={120} y={40}>
          <div className="rounded-xl border border-surface-800 bg-surface-900 overflow-hidden">
            {/* window chrome */}
            <div className="flex items-center gap-2 px-4 py-3 border-b border-surface-800 bg-surface-950">
              <span className="w-2.5 h-2.5 rounded-full bg-surface-700" />
              <span className="w-2.5 h-2.5 rounded-full bg-surface-700" />
              <span className="w-2.5 h-2.5 rounded-full bg-surface-700" />
              <div className="ml-3 px-3 py-1 rounded bg-surface-900 border border-surface-800 text-[11px] font-mono text-surface-500">
                ilovemyfiles.in/files
              </div>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-[200px_1fr]">
              {/* mock sidebar */}
              <div className="border-r border-surface-800 p-3 space-y-1 hidden sm:block">
                {['My Files', 'Search', 'Shared', 'Trash', 'Analytics'].map((item, i) => (
                  <div
                    key={item}
                    className={`flex items-center gap-2 px-2.5 py-1.5 rounded-md text-xs ${
                      i === 0 ? 'bg-surface-800 text-white' : 'text-surface-500'
                    }`}
                  >
                    <span className="w-3 h-3 rounded-sm bg-surface-700" />
                    {item}
                  </div>
                ))}
              </div>
              {/* mock file list */}
              <div className="p-4 sm:p-5">
                <div className="flex items-center justify-between mb-4">
                  <div className="h-4 w-24 rounded bg-surface-800" />
                  <div className="h-7 w-20 rounded-md bg-white/90" />
                </div>
                {[
                  ['Documents', '12 files'],
                  ['Photos', '48 files'],
                  ['project-spec.pdf', '2.4 MB'],
                  ['backup.zip', '1.1 GB'],
                  ['notes.md', '4 KB'],
                ].map(([name, meta], i) => (
                  <div
                    key={name}
                    className={`flex items-center gap-3 py-2.5 ${
                      i < 4 ? 'border-b border-surface-800/70' : ''
                    }`}
                  >
                    <span className="w-4 h-4 rounded-sm bg-surface-700 shrink-0" />
                    <span className="text-xs text-surface-300">{name}</span>
                    <span className="ml-auto text-xs text-surface-600 font-mono">{meta}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </Reveal>
        </div>
      </section>

      {/* ── Footer ───────────────────────────────────────────────────────── */}
      <footer className="border-t border-surface-800 bg-rose-50/60">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-10 flex flex-col sm:flex-row items-center justify-between gap-5">
          <div className="flex items-center gap-2">
            <div className="w-6 h-6 bg-white rounded flex items-center justify-center">
              <Cloud className="w-3.5 h-3.5 text-surface-950" />
            </div>
            <span className="text-sm text-surface-400">
              © {new Date().getFullYear()} ilovemyfiles.in — built by Piyush Raj
            </span>
          </div>
          <div className="flex items-center gap-2">
            <a
              href="https://www.linkedin.com/in/PLACEHOLDER"
              target="_blank"
              rel="noreferrer noopener"
              aria-label="LinkedIn"
              className="p-2 rounded-md text-surface-400 hover:text-white hover:bg-surface-800 transition-colors"
            >
              <SocialIcon path={SOCIAL_ICON_PATHS.linkedin} label="LinkedIn" />
            </a>
            <a
              href="https://github.com/PLACEHOLDER"
              target="_blank"
              rel="noreferrer noopener"
              aria-label="GitHub"
              className="p-2 rounded-md text-surface-400 hover:text-white hover:bg-surface-800 transition-colors"
            >
              <SocialIcon path={SOCIAL_ICON_PATHS.github} label="GitHub" />
            </a>
            <a
              href="https://www.youtube.com/@PLACEHOLDER"
              target="_blank"
              rel="noreferrer noopener"
              aria-label="YouTube"
              className="p-2 rounded-md text-surface-400 hover:text-white hover:bg-surface-800 transition-colors"
            >
              <SocialIcon path={SOCIAL_ICON_PATHS.youtube} label="YouTube" />
            </a>
          </div>
        </div>
      </footer>

    </div>
  );
}
