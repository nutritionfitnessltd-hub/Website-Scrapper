import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
// Editable source lives in site/. No packages or database required.
for (const script of ['build.mjs', 'check.mjs']) {
  const result = spawnSync(process.execPath, [script], { cwd: 'site', stdio: 'inherit', env: process.env });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
fs.rmSync('public', { recursive: true, force: true });
fs.cpSync('site/public', 'public', { recursive: true });
