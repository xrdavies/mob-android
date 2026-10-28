// make release writes the version into the files that carry it, then commits them before tagging.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { version, versionCode } from './release.mjs';

export const VERSION_FILES = ['app/build.gradle.kts'];

// The version the local-build defaults agree on. read(file) returns a file's text; by default from root.
export function readVersion(root, read = file => readFileSync(join(root, file), 'utf8')) {
  const file = VERSION_FILES[0];
  const text = read(file);
  const codes = [...text.matchAll(/versionCode = System\.getenv\("MOB_VERSION_CODE"\)\?\.toInt\(\) \?: (\d+)/g)];
  const names = [...text.matchAll(/versionName = System\.getenv\("MOB_VERSION_NAME"\) \?: "([^"]*)"/g)];
  if (codes.length !== 1 || names.length !== 1) throw Error(`${file}: expected one versionCode and one versionName default`);
  const name = version(names[0][1]);
  if (Number(codes[0][1]) !== versionCode(name)) throw Error(`${file}: versionCode ${codes[0][1]} does not match versionName ${name} (expected ${versionCode(name)})`);
  return name;
}

// Updates the local-build defaults (CI still injects the tag's values); returns the files that
// changed. Nothing is written unless the file has the expected shape.
export function bumpVersion(root, version) {
  const file = 'app/build.gradle.kts';
  const before = readFileSync(join(root, file), 'utf8');
  const code = /(versionCode = System\.getenv\("MOB_VERSION_CODE"\)\?\.toInt\(\) \?: )\d+/g;
  const name = /(versionName = System\.getenv\("MOB_VERSION_NAME"\) \?: ")[^"]*(")/g;
  if ((before.match(code) ?? []).length !== 1 || (before.match(name) ?? []).length !== 1) throw Error(`${file}: expected one versionCode and one versionName default`);
  const after = before.replace(code, `$1${versionCode(version)}`).replace(name, `$1${version}$2`);
  if (after === before) return [];
  writeFileSync(join(root, file), after);
  return [file];
}

export const PARTS = ['major', 'minor', 'patch'];

// The next version after current for make release-major, -minor or -patch.
export function increment(current, part) {
  const i = PARTS.indexOf(part);
  if (i < 0) throw Error(`Expected one of ${PARTS.join(', ')}`);
  const n = version(current).split('.').map(Number);
  n[i]++;
  n.fill(0, i + 1);
  return n.join('.');
}

export function compareVersions(a, b) {
  const x = version(a).split('.').map(BigInt), y = version(b).split('.').map(BigInt);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
}
