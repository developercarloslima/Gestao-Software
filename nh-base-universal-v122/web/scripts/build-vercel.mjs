import { cp, mkdir, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dist = path.join(projectRoot, 'dist');
const excluded = new Set([
  'dist', 'node_modules', 'scripts', 'api', '.git', '.vercel', '.vscode',
  'Dockerfile', 'nginx.conf', 'docker-entrypoint-nh.sh', 'package.json',
  'package-lock.json', 'vercel.mjs', 'README.md', '.dockerignore', '.gitignore', '.hintrc'
]);

await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });

for (const entry of await (await import('node:fs/promises')).readdir(projectRoot, { withFileTypes: true })) {
  if (excluded.has(entry.name)) continue;
  await cp(path.join(projectRoot, entry.name), path.join(dist, entry.name), { recursive: true });
}

const normalize = (value) => String(value || '').trim().replace(/\/+$/, '');
const backendBaseUrl = normalize(process.env.BACKEND_RENDER_URL);
const sgaUrl = String(process.env.SGA_URL || '').trim();
const teamWhatsapp = String(process.env.TEAM_WHATSAPP_NUMBER || '').trim();

if (!backendBaseUrl) {
  throw new Error('Configure BACKEND_RENDER_URL no projeto da Vercel antes do deploy.');
}

const config = `(() => {
  const backendBaseUrl = ${JSON.stringify(backendBaseUrl)};
  window.NH_CONFIG = {
    sgaUrl: ${JSON.stringify(sgaUrl)},
    teamWhatsapp: ${JSON.stringify(teamWhatsapp)},
    backendBaseUrl
  };
  window.NH_API = {
    backend(path) {
      const normalizedPath = String(path || '').startsWith('/') ? String(path) : '/' + path;
      return backendBaseUrl + normalizedPath;
    }
  };
})();
`;

await mkdir(path.join(dist, 'shared'), { recursive: true });
await writeFile(path.join(dist, 'shared', 'config.js'), config, 'utf8');
console.log(`Frontend preparado para usar o backend: ${backendBaseUrl}`);
