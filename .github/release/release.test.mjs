import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { baseURL, checkPromotion, prepareDistribution, publish, sha256, versionCode } from './release.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'mob-android-release-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const assets = join(root, 'assets'), output = join(root, 'output');
  mkdirSync(assets);
  writeFileSync(join(assets, 'mob-android_1.2.3.apk'), 'fixture apk');
  const manifests = prepareDistribution(assets, output, { version: '1.2.3', base: 'https://download.example.com', date: '2026-10-14T00:00:00Z', notes: 'New release' });
  return { output, manifests };
}

test('version codes increase with semantic versions and reject unsafe origins', () => {
  assert.equal(versionCode('1.2.3'), 1002003);
  assert.ok(versionCode('0.10.0') > versionCode('0.9.999'));
  assert.ok(versionCode('1.0.0') > versionCode('0.999.999'));
  assert.throws(() => versionCode('1.1000.0'), /range/);
  assert.throws(() => versionCode('01.2.3'));
  assert.throws(() => baseURL(''), /Missing DOWNLOAD_BASE_URL/);
  for (const base of ['http://download.example.com', 'https://user:pass@example.com', 'https://example.com/path', 'https://example.com/?token=x', 'https://pub-123.r2.dev']) {
    assert.throws(() => baseURL(base));
  }
});

test('manifest points at the versioned APK and its checksum', t => {
  const { output, manifests } = fixture(t);
  const manifest = manifests.android;
  assert.deepEqual(Object.keys(manifests), ['android']);
  assert.equal(manifest.download.url, 'https://download.example.com/android/v1.2.3/mob-android_1.2.3.apk');
  assert.equal(manifest.download.sha256, sha256(readFileSync(join(output, 'android/v1.2.3/mob-android_1.2.3.apk'))));
  assert.equal(manifest.checksums_url, 'https://download.example.com/android/v1.2.3/SHA256SUMS');
  assert.equal(manifest.version_code, 1002003);
  assert.equal(readFileSync(join(output, 'android/v1.2.3/SHA256SUMS'), 'utf8'), `${manifest.download.sha256}  mob-android_1.2.3.apk\n`);
});

function remote(events, overrides = {}) {
  return {
    async readJSON() { return null; }, async digest() { return null; },
    async upload(key, path, immutable) { events.push({ type: 'upload', key, immutable }); },
    async verify(key) { events.push({ type: 'verify', key }); },
    ...overrides,
  };
}

test('all downloads are verified before the stable pointer is published', async t => {
  const { output, manifests } = fixture(t), events = [];
  await publish(output, manifests, remote(events));
  const firstMutable = events.findIndex(e => e.type === 'upload' && !e.immutable);
  assert.ok(events.slice(0, firstMutable).some(e => e.type === 'verify'));
  assert.ok(events.slice(firstMutable).every(e => e.type === 'upload' && !e.immutable));
  assert.deepEqual(events.filter(e => e.key.endsWith('/latest.json')).map(e => e.key), ['android/latest.json']);
});

test('failed verification, changed files and backwards promotions never update stable', async t => {
  const { output, manifests } = fixture(t);
  let events = [];
  await assert.rejects(publish(output, manifests, remote(events, { async verify() { throw Error('Public checksum mismatch'); } })), /checksum/);
  assert.ok(events.every(e => e.immutable));
  events = [];
  await assert.rejects(publish(output, manifests, remote(events, { async digest() { return 'different'; } })), /overwrite/);
  assert.equal(events.length, 0);
  assert.throws(() => checkPromotion({ version: '1.10.0' }, { version: '1.2.3' }), /backwards/);
});

test('publication only reads and writes the android namespace', async t => {
  const { output, manifests } = fixture(t), events = [], reads = [];
  await publish(output, manifests, remote(events, {
    async readJSON(key) { reads.push(key); return null; },
    async digest(key) { reads.push(key); return null; },
  }));
  assert.ok(reads.length > 0);
  assert.ok([...reads, ...events.map(e => e.key)].every(key => key.startsWith('android/')));
});
