import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '@clerk/clerk-react';
import { Folder, FolderPlus, Home, Upload, X } from 'lucide-react';
import { FileIcon } from '../components/FileIcon';
import { formatBytes, formatRelativeDate } from '../utils/format';

const REPO_URL = 'https://github.com/Piyusss/ilovemyfiles.in';
const AUTHOR_URL = 'https://www.linkedin.com/in/piy777sus';

const container = 'mx-auto w-full max-w-[1150px] px-5 sm:px-6';

// Near-black outline that follows each element's border radius.
const focusRing =
  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-surface-50';

const textLink = `rounded-sm transition-colors hover:text-surface-500 ${focusRing}`;

// Standalone text links get vertical padding so the tap target is taller than the text.
const standaloneLink = `inline-block py-2 ${textLink}`;

// Footer links sit in grey text, so hover adds an underline instead of turning grey.
const footerLink = `rounded-sm text-surface-50 underline-offset-4 hover:underline ${focusRing}`;

function PrimaryLink({ to, small = false, children }: { to: string; small?: boolean; children: ReactNode }) {
  return (
    <Link
      to={to}
      className={`inline-flex items-center rounded-full bg-surface-50 font-medium text-canvas transition-colors hover:bg-surface-300 ${
        small ? 'h-9 px-4 text-sm' : 'h-11 px-6 text-[15px]'
      } ${focusRing}`}
    >
      {children}
    </Link>
  );
}

/* ── Content ──────────────────────────────────────────────────────────────── */

// Every number here comes from the code: chunkedUpload.ts, SchedulerService,
// app.version-retention, the share dialog's expiry options and the public
// /api/share routes. The sentence explains; the facts column holds the numbers,
// so the two never repeat each other. Non-breaking spaces keep units attached.
const steps = [
  {
    title: 'Hashing',
    body: 'Your browser fingerprints each file before sending it. If the same content is already stored, nothing is uploaded.',
    facts: ['SHA-256', 'Files up to 500 MB'],
  },
  {
    title: 'Uploading',
    body: 'Files are split into parts and sent in parallel. A part that fails is retried on its own, so the file doesn\'t start over.',
    facts: ['5 MB parts', '3 at a time', '2 retries per part'],
  },
  {
    title: 'Versioning',
    body: 'Saving over a file keeps the old copy, which you can restore later. Deleted files wait in the trash.',
    facts: ['Last 10 versions', '30-day trash'],
  },
  {
    title: 'Sharing',
    body: 'Send anyone a link to a file. They can download it without an account.',
    facts: ['Optional password', 'Expiry from 1 hour to 30 days'],
  },
];

const providers = ['Vercel', 'Render', 'Neon', 'Upstash', 'Backblaze B2', 'Clerk'];

/* ── Product preview ──────────────────────────────────────────────────────────
   A miniature of the "My files" view in FilesPage: same labels, icons, colour
   tokens and formatters, filled with sample data. The one deviation: small grey
   text on the #f5f5f5 cards uses surface-400 instead of surface-500, because
   the app's pairing is 4.34:1 and the landing page has to meet AA. */

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

const previewFolders = [
  { name: 'Coursework', itemCount: 14, age: 3 * DAY },
  { name: 'Photos', itemCount: 86, age: 26 * DAY },
];

const previewFiles = [
  { name: 'thesis-draft.pdf', contentType: 'application/pdf', size: 2_516_582, age: 2 * HOUR },
  { name: 'site-plan.png', contentType: 'image/png', size: 1_153_434, age: 5 * DAY },
  {
    name: 'budget-2026.xlsx',
    contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    size: 49_152,
    age: 9 * DAY,
  },
];

const previewUpload = { name: 'lecture-recording.mp4', contentType: 'video/mp4', progress: 64 };

const plural = (n: number, word: string) => `${n} ${word}${n !== 1 ? 's' : ''}`;

// Dates are relative to page load, so they read like a live listing.
const loadedAt = Date.now();
const created = (age: number) => formatRelativeDate(new Date(loadedAt - age).toISOString());

const previewRows = [
  ...previewFolders.map((f) => ({
    name: f.name,
    contentType: null,
    size: plural(f.itemCount, 'file'),
    created: created(f.age),
  })),
  ...previewFiles.map((f) => ({
    name: f.name,
    contentType: f.contentType,
    size: formatBytes(f.size),
    created: created(f.age),
  })),
];

function FilesPreview() {
  const th = 'px-3 py-2.5 text-[10px] font-medium uppercase tracking-wider text-surface-400 sm:px-4';
  const td = 'px-3 py-2.5 text-xs text-surface-400 sm:px-4';

  return (
    <figure aria-labelledby="preview-caption" className="rounded-xl border border-surface-800 bg-surface-950 p-4 sm:p-6">
      <figcaption id="preview-caption" className="sr-only">
        The My files view with two folders and three files, while lecture-recording.mp4 uploads at 64%.
      </figcaption>

      <div aria-hidden="true" className="space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-lg font-bold leading-tight text-surface-50">My files</p>
            <p className="mt-1 text-xs text-surface-400">
              {plural(previewFolders.length, 'folder')}, {plural(previewFiles.length, 'file')}
              <span className="ml-1.5 text-brand-400">· 1 uploading</span>
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            <span className="inline-flex items-center gap-1.5 rounded-md border border-surface-700 px-2.5 py-1.5 text-xs font-medium text-surface-200">
              <FolderPlus className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">New folder</span>
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-md bg-surface-50 px-2.5 py-1.5 text-xs font-medium text-surface-950">
              <Upload className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">Upload</span>
            </span>
          </div>
        </div>

        <span className="inline-flex items-center gap-1 rounded-lg bg-surface-800/50 px-2 py-1 text-xs font-medium text-surface-50">
          <Home className="h-3 w-3" />
          Root
        </span>

        <div className="rounded-lg border border-surface-800 bg-surface-900 p-3">
          <p className="mb-2 text-[10px] font-medium uppercase tracking-wider text-surface-400">Transfers</p>
          <div className="flex items-center gap-2.5 rounded-xl bg-surface-800/40 p-2.5">
            <FileIcon contentType={previewUpload.contentType} className="h-4 w-4 shrink-0 text-surface-400" />
            <div className="min-w-0 flex-1">
              <div className="mb-1 flex items-center justify-between">
                <span className="truncate pr-2 text-xs text-surface-50">{previewUpload.name}</span>
                <span className="shrink-0 text-[11px] text-surface-400">{previewUpload.progress}%</span>
              </div>
              <div className="h-1 w-full overflow-hidden rounded-full bg-surface-700">
                <div className="h-full rounded-full bg-surface-50" style={{ width: `${previewUpload.progress}%` }} />
              </div>
            </div>
            <X className="h-3.5 w-3.5 shrink-0 text-surface-500" />
          </div>
        </div>

        <div className="overflow-hidden rounded-lg border border-surface-800 bg-surface-900">
          <table className="w-full table-fixed text-left">
            <thead>
              <tr className="border-b border-surface-700/50">
                <th className={th}>Name</th>
                <th className={`${th} w-[6.5rem] sm:w-28`}>Size / Items</th>
                <th className={`${th} hidden w-24 sm:table-cell`}>Created</th>
              </tr>
            </thead>
            <tbody>
              {previewRows.map((r, i) => (
                <tr key={r.name} className={i < previewRows.length - 1 ? 'border-b border-surface-800/60' : ''}>
                  <td className="px-3 py-2.5 sm:px-4">
                    <span className="flex min-w-0 items-center gap-2.5">
                      {r.contentType ? (
                        <FileIcon contentType={r.contentType} className="h-4 w-4 shrink-0 text-surface-400" />
                      ) : (
                        <Folder className="h-4 w-4 shrink-0 text-surface-300" />
                      )}
                      <span className={`truncate text-xs text-surface-200 ${r.contentType ? '' : 'font-medium'}`}>
                        {r.name}
                      </span>
                    </span>
                  </td>
                  <td className={td}>{r.size}</td>
                  <td className={`${td} hidden sm:table-cell`}>{r.created}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </figure>
  );
}

/* ─────────────────────────────────────────────────────────────────────────────
   Page
   ───────────────────────────────────────────────────────────────────────────── */

export function LandingPage() {
  // Signed-out links render first, so the page never waits on Clerk to load.
  const { isSignedIn } = useAuth();
  const primary = isSignedIn
    ? { to: '/files', label: 'Open app' }
    : { to: '/sign-up', label: 'Create account' };

  return (
    <div className="min-h-screen bg-canvas text-surface-50">
      <header className={`${container} flex h-16 items-center justify-between`}>
        <Link to="/" className={`rounded-sm text-[15px] font-medium tracking-[-0.01em] ${focusRing}`}>
          ilovemyfiles.in
        </Link>
        <nav aria-label="Account" className="flex items-center gap-5">
          {!isSignedIn && (
            <Link to="/sign-in" className={`text-[15px] ${standaloneLink}`}>
              Sign in
            </Link>
          )}
          <PrimaryLink to={primary.to} small>
            {primary.label}
          </PrimaryLink>
        </nav>
      </header>

      <main>
        <section
          aria-labelledby="hero-heading"
          className={`${container} grid items-center gap-14 pt-14 sm:pt-20 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)] lg:gap-16 lg:pt-24`}
        >
          <div>
            <h1
              id="hero-heading"
              className="text-balance text-[40px] font-medium leading-[1.08] tracking-[-0.02em] sm:text-[48px] lg:text-[56px] lg:leading-[1.05]"
            >
              Store, version and share your files.
            </h1>
            <p className="mt-6 max-w-[30rem] text-pretty text-[17px] leading-relaxed text-surface-500 sm:text-lg">
              Every account gets 5&nbsp;GB, and files move directly between your browser and storage without passing
              through the app server.
            </p>
            <div className="mt-9 flex flex-wrap items-center gap-x-7 gap-y-4">
              <PrimaryLink to={primary.to}>{primary.label}</PrimaryLink>
              <a href="#how-it-works" className={`text-[15px] font-medium ${standaloneLink}`}>
                How it works <span aria-hidden="true">→</span>
              </a>
            </div>
          </div>
          <FilesPreview />
        </section>

        {/* Spec table: step name, a plain sentence, then the hard numbers. */}
        <section id="how-it-works" aria-labelledby="how-heading" className={`${container} mt-24 scroll-mt-8 lg:mt-32`}>
          <h2 id="how-heading" className="text-[28px] font-medium leading-tight tracking-[-0.015em] sm:text-[32px]">
            How it works
          </h2>
          <p className="mt-3 max-w-xl text-pretty text-[17px] leading-relaxed text-surface-500">
            What happens between picking a file and sending someone a link.
          </p>
          <ol className="mt-10 divide-y divide-surface-800 border-t border-surface-800 lg:mt-12">
            {steps.map((step) => (
              <li
                key={step.title}
                className="grid gap-y-2 py-6 last:pb-0 md:grid-cols-[minmax(0,2.5fr)_minmax(0,6.5fr)_minmax(0,3fr)] md:items-baseline md:gap-x-8 md:py-7 md:last:pb-0 lg:gap-x-12"
              >
                <h3 className="text-[17px] font-medium">{step.title}</h3>
                <p className="text-pretty leading-relaxed text-surface-500">{step.body}</p>
                <ul className="mt-2 space-y-1 text-[15px] md:mt-0">
                  {step.facts.map((fact) => (
                    <li key={fact}>{fact}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>
        </section>

        <section aria-labelledby="runs-on-heading" className={`${container} mt-24 lg:mt-32`}>
          <div className="flex flex-col gap-3 border-t border-surface-800 py-8 sm:flex-row sm:items-baseline sm:gap-10">
            <h2 id="runs-on-heading" className="shrink-0 text-[15px] font-medium">
              Runs on
            </h2>
            <ul className="flex flex-wrap gap-x-8 gap-y-2 text-[15px] text-surface-500">
              {providers.map((name) => (
                <li key={name}>{name}</li>
              ))}
            </ul>
          </div>
        </section>
      </main>

      <footer className={container}>
        <div className="flex flex-col gap-3 border-t border-surface-800 py-8 text-sm text-surface-500 sm:flex-row sm:items-center sm:justify-between">
          <p>
            An educational project by{' '}
            <a href={AUTHOR_URL} target="_blank" rel="noreferrer noopener" className={footerLink}>
              Piyush Raj
            </a>
          </p>
          <ul className="flex flex-wrap gap-x-6 gap-y-2">
            <li>
              <a href={REPO_URL} target="_blank" rel="noreferrer noopener" className={footerLink}>
                Source code
              </a>
            </li>
            <li>Built with React and Spring Boot</li>
          </ul>
        </div>
      </footer>
    </div>
  );
}
