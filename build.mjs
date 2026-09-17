import fs from 'node:fs';
import path from 'node:path';
import { brotliDecompressSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

// Lossless source transport. npm run unpack restores every original source file.
const manifest = JSON.parse(fs.readFileSync('manifest.json', 'utf8'));
const ref = process.env.LYONS_SOURCE_REF || 'lyons-interiors';
const base = `https://raw.githubusercontent.com/nutritionfitnessltd-hub/Website-Scrapper/${encodeURIComponent(ref)}/`;
async function readPart(file) {
  if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8');
  // Remote publishing uses the GitHub snapshot, never a secret or a third-party build service.
  let failure;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetch(base + file, { signal: AbortSignal.timeout(20000) });
      if (!response.ok) throw new Error(`GitHub source ${file}: HTTP ${response.status}`);
      return await response.text();
    } catch (error) {
      failure = error;
      await new Promise(resolve => setTimeout(resolve, (attempt + 1) * 1000));
    }
  }
  throw failure;
}
const encoded = (await Promise.all(manifest.parts.map(readPart))).join('');
const raw = brotliDecompressSync(Buffer.from(encoded, 'base64'));
if (createHash('sha256').update(raw).digest('hex') !== manifest.sha256) {
  throw new Error('Source integrity check failed. Build stopped.');
}
const sources = JSON.parse(raw.toString('utf8'));
const unpackOnly = process.argv.includes('--unpack');
const dest = path.resolve(unpackOnly ? 'editable-source' : '.lyons-build');
if (unpackOnly && fs.existsSync(dest)) throw new Error('editable-source already exists; rename it before unpacking to avoid overwriting your edits.');
fs.mkdirSync(dest, { recursive: true });
for (const [name, content] of Object.entries(sources)) {
  if (!manifest.files.includes(name) || typeof content !== 'string' || path.isAbsolute(name) || name.split(/[\\/]/).includes('..')) throw new Error('Unsafe source entry.');
  const target = path.join(dest, name);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content);
}
if (unpackOnly) {
  console.log('Original editable source restored to editable-source/. Run npm run build there.');
} else {
  for (const script of ['build.mjs', 'check.mjs']) {
    const result = spawnSync(process.execPath, [script], { cwd: dest, stdio: 'inherit', env: process.env });
    if (result.error) throw result.error;
    if (result.status !== 0) process.exit(result.status || 1);
  }
  fs.rmSync('public', { recursive: true, force: true });
  fs.cpSync(path.join(dest, 'public'), 'public', { recursive: true });
  console.log('Lyons Interiors: source integrity verified; static site ready in public/.');
}
