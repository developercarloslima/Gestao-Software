import type { ReactNode } from 'react';

export type IconName =
  | 'shield' | 'users' | 'chart' | 'buildings' | 'globe' | 'layers'
  | 'monitor' | 'calendar' | 'receipt' | 'wallet' | 'file' | 'settings'
  | 'tag' | 'arrow' | 'headset' | 'cloud' | 'backup' | 'server'
  | 'network' | 'search' | 'sun' | 'moon' | 'menu' | 'close' | 'check' | 'palette'
  | 'link' | 'lock' | 'spark' | 'user' | 'card' | 'pin' | 'mail';

const shapes: Record<IconName, ReactNode> = {
  shield: <><path d="M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z" /><path d="m9 12 2 2 4-4" /></>,
  users: <><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></>,
  chart: <><path d="M3 3v18h18" /><path d="M18 17V9M13 17V5M8 17v-3" /></>,
  buildings: <><rect x="3" y="7" width="13" height="14" rx="2" /><path d="M7 7V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v17M7 11h2m-2 4h2m-2 4h2m4-8h2m-2 4h2m-2 4h2" /></>,
  globe: <><circle cx="12" cy="12" r="10" /><path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10Z" /></>,
  layers: <><path d="m12 2 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5M3 17l9 5 9-5" /></>,
  monitor: <><rect x="2" y="3" width="20" height="14" rx="2" /><path d="M8 21h8m-4-4v4" /></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 11h18" /></>,
  receipt: <><path d="M4 2v20l4-2 4 2 4-2 4 2V2l-4 2-4-2-4 2-4-2Z" /><path d="M8 9h8m-8 4h6" /></>,
  wallet: <><rect x="2" y="5" width="20" height="15" rx="2" /><path d="M2 9h20m-5 4h1" /><path d="M6 5V3h12v2" /></>,
  file: <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6M8 13h8m-8 4h8" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="m19.4 15 .1.1 1.4 1.1-1.4 2.4-1.7-.6a8 8 0 0 1-1.7 1l-.3 1.8h-2.8l-.3-1.8a8 8 0 0 1-1.7-1l-1.7.6-1.4-2.4 1.4-1.1a7 7 0 0 1 0-2l-1.4-1.1 1.4-2.4 1.7.6a8 8 0 0 1 1.7-1l.3-1.8h2.8l.3 1.8a8 8 0 0 1 1.7 1l1.7-.6 1.4 2.4-1.4 1.1a7 7 0 0 1 0 2Z" transform="translate(-1 -1) scale(1.08)" /></>,
  tag: <><path d="M20.6 13.4 11 3.8A2.7 2.7 0 0 0 9.1 3H4a1 1 0 0 0-1 1v5.1c0 .7.3 1.4.8 1.9l9.6 9.6a2.7 2.7 0 0 0 3.8 0l3.4-3.4a2.7 2.7 0 0 0 0-3.8Z" /><circle cx="7.5" cy="7.5" r="1.2" /></>,
  arrow: <><path d="M5 12h14m-6-6 6 6-6 6" /></>,
  headset: <><path d="M3 14v-3a9 9 0 0 1 18 0v3" /><path d="M5 14H4a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h3v-6H5Zm14 0h1a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-3v-6h2Z" /></>,
  cloud: <><path d="M20 16.2A4.5 4.5 0 0 0 18 7.5h-1.3A7 7 0 0 0 3.5 10 4 4 0 0 0 4 18h15" /><path d="m8 14 4-4 4 4m-4-4v9" /></>,
  backup: <><path d="M4 4v6h6M20 20v-6h-6" /><path d="M5.5 15a8 8 0 0 0 13 2l1.5-1M18.5 9a8 8 0 0 0-13-2L4 8" /><path d="m9 13 3-3 3 3" /></>,
  server: <><rect x="3" y="3" width="18" height="8" rx="2" /><rect x="3" y="13" width="18" height="8" rx="2" /><path d="M7 7h.01M7 17h.01m4-10h6m-6 10h6" /></>,
  network: <><rect x="9" y="2" width="6" height="6" rx="1" /><rect x="2" y="16" width="6" height="6" rx="1" /><rect x="16" y="16" width="6" height="6" rx="1" /><path d="M12 8v4m-7 4v-2a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v2" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2m10-10h-2M4 12H2m17.07 7.07-1.42-1.42M6.35 6.35 4.93 4.93m14.14 0-1.42 1.42M6.35 17.65l-1.42 1.42" /></>,
  moon: <path d="M20.9 13A9 9 0 0 1 11 3.1 9 9 0 1 0 20.9 13Z" />,
  menu: <><path d="M4 6h16M4 12h16M4 18h16" /></>,
  close: <><path d="m18 6-12 12M6 6l12 12" /></>,
  check: <><path d="m5 12 4 4L19 6" /></>,
  palette: <><path d="M12 3a9 9 0 1 0 0 18h1.2a2 2 0 0 0 1.5-3.3c-.8-.9-.2-2.2 1-2.2H18a3 3 0 0 0 3-3c0-5.2-4-9.5-9-9.5Z" /><circle cx="7.5" cy="10" r=".8" /><circle cx="11" cy="7" r=".8" /><circle cx="16" cy="9" r=".8" /></>,
  link: <><path d="M10 13a5 5 0 0 0 7.1 0l3-3A5 5 0 0 0 13 2.9l-1.7 1.7" /><path d="M14 11a5 5 0 0 0-7.1 0l-3 3A5 5 0 1 0 11 21.1l1.7-1.7" /></>,
  lock: <><rect x="4" y="10" width="16" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3" /></>,
  spark: <><path d="m12 3 1.9 5.8L20 11l-6.1 2.2L12 19l-1.9-5.8L4 11l6.1-2.2L12 3Z" /><path d="m19 14 1 2.5 2 1-2 1L19 21l-1-2.5-2-1 2-1 1-2.5Z" /></>,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></>,
  card: <><rect x="2" y="4" width="20" height="16" rx="3" /><path d="M2 10h20m-15 5h3" /></>,
  pin: <><path d="M20 10c0 5-8 12-8 12S4 15 4 10a8 8 0 1 1 16 0Z" /><circle cx="12" cy="10" r="2.5" /></>,
  mail: <><rect x="3" y="5" width="18" height="14" rx="2" /><path d="m3 7 9 6 9-6" /></>,
};

export function Icon({ name, size = 24, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {shapes[name]}
    </svg>
  );
}
