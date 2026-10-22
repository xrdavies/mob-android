// make release writes the version into the files that carry it, then commits them before tagging.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { versionCode } from './release.mjs';

export const VERSION_FILES = ['app/build.gradle.kts'];

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
