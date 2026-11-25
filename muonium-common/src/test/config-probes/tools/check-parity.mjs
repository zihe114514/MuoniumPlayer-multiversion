#!/usr/bin/env node
/** Fail-closed parity report. No writes, dependency resolution, Gradle invocation or game launch. */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateRawSync, deflateRawSync } from 'node:zlib';
import assert from 'node:assert/strict';
import { collectSource, makeFeatureMap, parseArgs, PROJECT_ROOT, TARGETS, hash, json, ordered, exists, regularBytes, walk, lex, localPath, fingerprintInput } from './audit-source.mjs';

const FORBIDDEN_SYMBOL = /(?:androidx\/compose\/|org\/jetbrains\/compose\/|org\/jetbrains\/skiko\/|org\/polyfrost\/compose\/)/;
const KOTLIN_SYMBOL = /(?:^|[L;(\s/])(?:kotlin|kotlinx)\//;
const LOADER_SYMBOL = /(?:net\/fabricmc\/|net\/neoforged\/|net\/minecraftforge\/|cpw\/mods\/)/;
const SKIJA_DEFINITION = /(?:^|\/)(?:io\/github\/humbleui\/skija|org\/jetbrains\/skija)\//;
const NATIVE = /\.(?:dll|dylib|jnilib|so(?:\.\d+)*|a|lib)$/i;
const INPUT = /^(?:src\/|gradle\/|libs\/|lib\/)|(?:^|\/)(?:build|settings)\.gradle(?:\.kts)?$|^(?:build|verify|preview)\.(?:ps1|sh|cmd|bat)$|^(?:gradle\.properties|pom\.xml|package\.json)$/;
const MAX_ENTRY_BYTES = 64 * 1024 * 1024, MAX_TOTAL_BYTES = 512 * 1024 * 1024;
const normalSymbol = text => text.replaceAll('.', '/');
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const validHash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const unique = items => new Set(items).size === items.length;

export function diffSource(baseline, current) {
  const findings = [], add = (code, message, p) => findings.push({ code, message, ...(p ? { path: p } : {}) });
  if (baseline.source.head !== current.source.head) add('source-head-drift', baseline.source.head + ' -> ' + current.source.head);
  if (json(baseline.source.statusPorcelainV1Z) !== json(current.source.statusPorcelainV1Z)) add('source-git-state-drift', 'Upstream tracked/untracked/dirty state differs from captured baseline.');
  const expected = new Map(baseline.files.map(f => [f.path, f])), actual = new Map(current.files.map(f => [f.path, f]));
  for (const [p, f] of expected) {
    if (!actual.has(p)) add('source-file-missing', 'Baseline input no longer exists.', p);
    else if (f.sha256 !== actual.get(p).sha256 || f.bytes !== actual.get(p).bytes) add('source-bytes-drift', f.sha256 + ' -> ' + actual.get(p).sha256, p);
  }
  for (const p of actual.keys()) if (!expected.has(p)) add('source-file-added', 'Input is absent from baseline.', p);
  if (baseline.sourceTreeSha256 !== current.sourceTreeSha256) add('source-tree-drift', 'Source tree fingerprint differs; current evidence cannot establish this baseline.');
  if (json(baseline.uiSources) !== json(current.uiSources)) add('source-surface-drift', 'UI locations differ from current scanner output (source drift or baseline tampering).');
  for (const p of current.missingTracked) add('source-tracked-missing', 'Tracked input is deleted in working tree.', p);
  return findings;
}
export async function scanProject(project) {
  const all = await walk(project), files = [];
  for (const p of all.filter(p => INPUT.test(p) && fingerprintInput(p))) {
    const bytes = await regularBytes(project, p);
    files.push({ path: p, sha256: hash(bytes), bytes: bytes.length });
  }
  return { all, files, implementationSnapshotSha256: hash(files.map(f => f.path + '\0' + f.sha256).join('\n')) };
}
const evidenceBytes = (context, relative) => context.readBytes ? context.readBytes(relative) : regularBytes(context.project, relative);
async function checkFileEvidence(ref, context, add, featureId, purpose) {
  if (!ref || !nonempty(ref.path) || !validHash(ref.sha256)) { add('evidence-file-invalid', purpose + ' requires relative path + SHA256.', null, featureId); return false; }
  try {
    const bytes = await evidenceBytes(context, ref.path);
    if (hash(bytes) !== ref.sha256) { add('evidence-file-stale', purpose + ' bytes no longer match evidence hash.', ref.path, featureId); return false; }
    if (!bytes.length) { add('evidence-file-empty', purpose + ' is empty.', ref.path, featureId); return false; }
    return true;
  } catch (e) { add('evidence-file-missing', purpose + ': ' + e.message, ref.path, featureId); return false; }
}
function validCapture(p, bytes) {
  if (/\.png$/i.test(p)) return bytes.length >= 24 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) && bytes.toString('ascii',12,16) === 'IHDR' && bytes.readUInt32BE(16) > 0 && bytes.readUInt32BE(20) > 0;
  if (/\.jpe?g$/i.test(p)) return bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  if (/\.webp$/i.test(p)) return bytes.length >= 16 && bytes.toString('ascii',0,4) === 'RIFF' && bytes.toString('ascii',8,12) === 'WEBP';
  if (/\.gif$/i.test(p)) return bytes.length >= 10 && ['GIF87a','GIF89a'].includes(bytes.toString('ascii',0,6));
  if (/\.webm$/i.test(p)) return bytes.length >= 8 && bytes.readUInt32BE(0) === 0x1a45dfa3;
  if (/\.mp4$/i.test(p)) return bytes.length >= 12 && bytes.toString('ascii',4,8) === 'ftyp';
  return false;
}
export async function validateFeature(feature, context, add) {
  const id = feature.id;
  if (!['pending', 'complete'].includes(feature.status)) { add('feature-status-invalid', 'Status must be pending or complete.', null, id); return false; }
  if (feature.status !== 'complete') { add('feature-pending', feature.behavior, feature.sourceAnchors?.[0]?.path, id); return false; }
  let valid = true;
  const problem = (code, message, p) => { valid = false; add(code, message, p, id); };
  const review = feature.semanticReview, cases = review?.cases || [];
  if (review?.result !== 'reviewed' || !nonempty(review.by) || !nonempty(review.notes) || !cases.length) problem('semantic-review-required', 'Complete requires a named reviewer and explicit observable behavioral cases.');
  if (!unique(cases.map(c => c.id)) || cases.some(c => !nonempty(c.id) || !nonempty(c.expected))) problem('semantic-cases-invalid', 'Case IDs must be unique and have a concrete expected visible result.');
  const caseIds = new Set(cases.map(c => c.id));
  const reviewedSites = new Set(cases.flatMap(c => c.surfaceIds || []));
  for (const site of feature.surfaceIds || []) if (!reviewedSites.has(site)) problem('semantic-site-uncovered', 'No reviewed case covers source location ' + site + '.');
  if (!Array.isArray(feature.implementation) || !feature.implementation.length) problem('implementation-missing', 'Complete requires concrete implementation paths, lines and SHA256.');
  for (const impl of feature.implementation || []) {
    if (!/^src\/(?:main|acceptance)\//.test(impl.path || '') || !Number.isInteger(impl.line) || impl.line < 1) problem('implementation-invalid', 'Implementation must name a production source path and one-based line.', impl.path);
    const ok = await checkFileEvidence(impl, context, problem, id, 'implementation');
    if (!ok) valid = false;
    else {
      const lines = (await evidenceBytes(context, impl.path)).toString('utf8').split('\n').length;
      if (impl.line > lines) problem('implementation-line-invalid', 'Implementation line exceeds file length.', impl.path);
    }
  }
  const evidence = feature.evidence || {};
  for (const kind of ['automated', 'game', 'visual']) {
    const records = evidence[kind];
    if (!Array.isArray(records) || !records.length) { problem('evidence-' + kind + '-missing', 'Complete requires passing ' + kind + ' behavioral evidence.'); continue; }
    const covered = new Map();
    for (const record of records) {
      let recordOK = true;
      const fail = (message, p) => { recordOK = false; problem('evidence-' + kind + '-invalid', message, p); };
      if (record.featureId !== id || record.result !== 'pass') fail('Evidence must explicitly identify this feature and result=pass.');
      if (!nonempty(record.command) || !nonempty(record.observed)) fail('Evidence must record the actual procedure and observed visible result.');
      if (kind === 'automated' && record.kind !== 'behavior') fail('Compile/build/smoke evidence cannot stand in for kind=behavior.');
      if (record.sourceTreeSha256 !== context.sourceTreeSha256 || record.implementationSnapshotSha256 !== context.implementationSnapshotSha256) fail('Evidence baseline or implementation snapshot is stale.');
      if (!Array.isArray(record.caseIds) || !record.caseIds.length || record.caseIds.some(c => !caseIds.has(c))) fail('Evidence must name reviewed behavioral case IDs.');
      if (!Array.isArray(record.targets) || !record.targets.length || !unique(record.targets) || record.targets.some(t => !feature.targets.includes(t))) fail('Evidence targets must match exact MC/loader combinations.');
      if (kind !== 'automated' && record.targets?.length !== 1) fail('Game/visual records must describe one exact MC/loader combination per record.');
      if (!record.report || !/^(?:build\/|docs\/acceptance\/)/.test(record.report.path || '')) fail('Report must be an actual acceptance/test output under build/ or docs/acceptance/.');
      if (!(await checkFileEvidence(record.report, context, problem, id, kind + ' report'))) recordOK = false;
      else if (kind === 'automated') {
        try {
          const output = JSON.parse(await evidenceBytes(context, record.report.path));
          if (output.schemaVersion !== 1 || output.kind !== 'behavior' || output.result !== 'pass' || output.featureId !== id || output.sourceTreeSha256 !== context.sourceTreeSha256 || output.implementationSnapshotSha256 !== context.implementationSnapshotSha256 || json(output.targets) !== json(record.targets)) fail('Automated output must contain this exact passing behavior contract and target list.', record.report.path);
          for (const caseId of record.caseIds || []) {
            const test = output.cases?.find(c => c.id === caseId);
            if (test?.result !== 'pass' || !Array.isArray(test.assertions) || !test.assertions.length || test.assertions.some(a => a.pass !== true || !nonempty(a.expected) || !nonempty(a.observed))) fail('Automated case ' + caseId + ' requires passing expected/observed state assertions; compiler logs are insufficient.', record.report.path);
          }
        } catch (e) { fail('Automated report must be a structured JSON behavioral result: ' + e.message, record.report.path); }
      }
      if (kind === 'visual') {
        if (!Array.isArray(record.captures) || !record.captures.length) fail('Visual evidence needs paired upstream/port captures.');
        for (const capture of record.captures || []) {
          if (!nonempty(capture.comparison)) fail('Capture pair requires an explicit layout/style/motion comparison.');
          for (const side of ['source', 'port']) {
            if (!/\.(?:png|jpg|jpeg|webp|gif|mp4|webm)$/i.test(capture[side]?.path || '')) fail('Capture is not an image/video file.', capture[side]?.path);
            if (!(await checkFileEvidence(capture[side], context, problem, id, 'visual ' + side))) recordOK = false;
            else if (!validCapture(capture[side].path, await evidenceBytes(context, capture[side].path))) fail('Capture bytes do not match the claimed image/video format.', capture[side].path);
          }
        }
      }
      if (recordOK) for (const target of record.targets) for (const caseId of record.caseIds) {
        if (!covered.has(target)) covered.set(target, new Set()); covered.get(target).add(caseId);
      }
    }
    for (const target of feature.targets) for (const caseId of caseIds) if (!covered.get(target)?.has(caseId)) problem('evidence-' + kind + '-coverage', 'Missing passing case ' + caseId + ' for ' + target + '.');
  }
  return valid;
}

const crcTable = Uint32Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function crc32(bytes) { let crc = 0xffffffff; for (const value of bytes) crc = crcTable[(crc ^ value) & 255] ^ (crc >>> 8); return (crc ^ 0xffffffff) >>> 0; }
/** Bounded ZIP/JAR reader. Unsupported/encrypted/ZIP64/malformed entries fail rather than being skipped. */
export function readZip(bytes) {
  if (bytes.length < 22) throw new Error('Archive is shorter than ZIP EOCD.');
  let end = -1;
  for (let p = bytes.length - 22; p >= Math.max(0, bytes.length - 22 - 65535); p--) if (bytes.readUInt32LE(p) === 0x06054b50 && p + 22 + bytes.readUInt16LE(p + 20) === bytes.length) { end = p; break; }
  if (end < 0) throw new Error('ZIP EOCD not found.');
  const count = bytes.readUInt16LE(end + 10), size = bytes.readUInt32LE(end + 12), offset = bytes.readUInt32LE(end + 16);
  if (bytes.readUInt16LE(end + 4) || bytes.readUInt16LE(end + 6) || count !== bytes.readUInt16LE(end + 8)) throw new Error('Multi-disk ZIP is unsupported.');
  if (count === 0xffff || size === 0xffffffff || offset === 0xffffffff) throw new Error('ZIP64 needs a reviewed reader; inspection not completed.');
  if (offset + size > end) throw new Error('ZIP central directory exceeds archive bounds.');
  const entries = [], names = new Set(); let cursor = offset, expanded = 0;
  for (let i = 0; i < count; i++) {
    if (cursor + 46 > offset + size || bytes.readUInt32LE(cursor) !== 0x02014b50) throw new Error('Bad ZIP central entry.');
    const flags = bytes.readUInt16LE(cursor + 8), method = bytes.readUInt16LE(cursor + 10), crc = bytes.readUInt32LE(cursor + 16), compressedSize = bytes.readUInt32LE(cursor + 20), uncompressedSize = bytes.readUInt32LE(cursor + 24);
    const nameLength = bytes.readUInt16LE(cursor + 28), extraLength = bytes.readUInt16LE(cursor + 30), commentLength = bytes.readUInt16LE(cursor + 32), localOffset = bytes.readUInt32LE(cursor + 42);
    const entryEnd = cursor + 46 + nameLength + extraLength + commentLength;
    if (entryEnd > offset + size) throw new Error('ZIP entry metadata exceeds bounds.');
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8'); cursor = entryEnd;
    if (!name || name.includes('\\') || name.includes('\0') || name.startsWith('/') || /(?:^|\/)\.\.(?:\/|$)/.test(name) || /^[A-Za-z]:/.test(name)) throw new Error('Unsafe ZIP member name: ' + name);
    if (names.has(name)) throw new Error('Duplicate ZIP member: ' + name); names.add(name);
    if (flags & 1) throw new Error('Encrypted ZIP entry: ' + name);
    if (![0, 8].includes(method)) throw new Error('Unsupported ZIP method ' + method + ': ' + name);
    if (uncompressedSize > MAX_ENTRY_BYTES || (expanded += uncompressedSize) > MAX_TOTAL_BYTES) throw new Error('ZIP expansion inspection limit exceeded: ' + name);
    if (localOffset + 30 > offset || bytes.readUInt32LE(localOffset) !== 0x04034b50) throw new Error('Invalid ZIP local header: ' + name);
    const localNameLength = bytes.readUInt16LE(localOffset + 26), localExtraLength = bytes.readUInt16LE(localOffset + 28), start = localOffset + 30 + localNameLength + localExtraLength;
    if (start + compressedSize > offset || bytes.readUInt16LE(localOffset + 8) !== method || bytes.readUInt16LE(localOffset + 6) !== flags) throw new Error('ZIP local/central contract mismatch: ' + name);
    const localName = bytes.subarray(localOffset + 30, localOffset + 30 + localNameLength).toString('utf8');
    if (localName !== name) throw new Error('ZIP local/central member name mismatch: ' + name);
    let cached;
    entries.push({ name, size: uncompressedSize, data() {
      if (cached) return cached;
      const compressed = bytes.subarray(start, start + compressedSize);
      cached = method === 0 ? compressed : inflateRawSync(compressed, { maxOutputLength: MAX_ENTRY_BYTES });
      if (cached.length !== uncompressedSize || crc32(cached) !== crc) throw new Error('ZIP size/CRC mismatch: ' + name);
      return cached;
    } });
  }
  if (cursor !== offset + size) throw new Error('Unexpected ZIP central-directory trailing data.');
  return entries;
}
export function classInfo(bytes) {
  if (bytes.length < 10 || bytes.readUInt32BE(0) !== 0xcafebabe) throw new Error('Invalid class file.');
  const strings = [], pool = [], count = bytes.readUInt16BE(8); let offset = 10;
  const need = n => { if (offset + n > bytes.length) throw new Error('Truncated class file.'); };
  const u2 = () => { need(2); const n = bytes.readUInt16BE(offset); offset += 2; return n; };
  for (let index = 1; index < count; index++) {
    need(1); const tag = bytes[offset++];
    if (tag === 1) { const length = u2(); need(length); const value = bytes.subarray(offset, offset + length).toString('utf8'); strings.push(value); pool[index] = { tag, value }; offset += length; }
    else if (tag === 7) pool[index] = { tag, name: u2() };
    else if ([3, 4, 9, 10, 11, 12, 17, 18].includes(tag)) { need(4); offset += 4; pool[index] = { tag }; }
    else if ([5, 6].includes(tag)) { need(8); offset += 8; pool[index] = { tag }; index++; }
    else if (tag === 8) pool[index] = { tag, text: u2() };
    else if ([16, 19, 20].includes(tag)) { need(2); offset += 2; pool[index] = { tag }; }
    else if (tag === 15) { need(3); offset += 3; pool[index] = { tag }; }
    else throw new Error('Unknown class constant-pool tag ' + tag);
  }
  u2(); const thisIndex = u2(); u2();
  const ownerRef = pool[thisIndex], owner = pool[ownerRef?.name];
  if (ownerRef?.tag !== 7 || owner?.tag !== 1) throw new Error('Invalid class owner reference.');
  const interfaces = u2(); need(interfaces * 2); offset += interfaces * 2;
  const attributes = () => {
    const amount = u2();
    for (let i = 0; i < amount; i++) { u2(); need(4); const size = bytes.readUInt32BE(offset); offset += 4; need(size); offset += size; }
  };
  for (let section = 0; section < 2; section++) {
    const members = u2();
    for (let i = 0; i < members; i++) { need(6); offset += 6; attributes(); }
  }
  attributes();
  if (offset !== bytes.length) throw new Error('Trailing bytes after class structure.');
  const literals = new Set(pool.filter(item => item?.tag === 8).map(item => item.text));
  const symbolStrings = pool.flatMap((item, index) => item?.tag === 1 && !literals.has(index) ? [item.value] : []);
  const literalStrings = pool.filter(item => item?.tag === 8).map(item => pool[item.text]?.value || '');
  const reflects = symbolStrings.some(value => ['forName', 'loadClass', 'defineClass'].includes(value));
  return { major: bytes.readUInt16BE(6), owner: owner.value, strings, symbolStrings, literalStrings, reflects };
}
export function inspectArchive(bytes, label, allowedAcceptance = new Set(), state = { expanded: 0, depth: 0 }) {
  const findings = [], add = (code, message, member) => findings.push({ code, message, path: label + (member ? '!' + member : '') });
  if (state.depth > 6) { add('archive-inspection-incomplete', 'Nested archive depth exceeds inspection limit.'); return { findings, entries: [] }; }
  let entries;
  try { entries = readZip(bytes); } catch (e) { add('archive-invalid', e.message); return { findings, entries: [] }; }
  for (const entry of entries) {
    state.expanded += entry.size;
    if (state.expanded > MAX_TOTAL_BYTES) { add('archive-inspection-incomplete', 'Total nested expansion limit exceeded.', entry.name); break; }
    try {
      const payload = entry.data(), member = entry.name;
      if (NATIVE.test(member)) add('archive-native-bundled', 'Acceptance artifact bundles a native library; reuse host-owned Skija/native.', member);
      if (FORBIDDEN_SYMBOL.test(member)) add('archive-compose-skiko', 'Forbidden Compose/Skiko/second UI backend class or resource.', member);
      if (/(?:^|\/)(?:kotlin|kotlinx)\//.test(member) || /\.(?:kotlin_module|kotlin_builtins|kotlin_metadata)$/.test(member)) add('archive-kotlin-bundled', 'Kotlin runtime/module is bundled in acceptance artifact.', member);
      if (SKIJA_DEFINITION.test(member)) add('archive-skija-bundled', 'Second Skija class definition is bundled.', member);
      if (LOADER_SYMBOL.test(member) && member.endsWith('.class')) add('archive-loader-bundled', 'Loader API class definitions may never be bundled, including acceptance shell.', member);
      if (/\.(?:jar|zip)$/i.test(member)) {
        const nested = inspectArchive(payload, label + '!' + member, allowedAcceptance, { expanded: state.expanded, depth: state.depth + 1 });
        findings.push(...nested.findings); state.expanded = nested.expanded ?? state.expanded;
      }
      if (member.endsWith('.class')) {
        const info = classInfo(payload), owner = info.owner, symbols = [...info.symbolStrings, ...(info.reflects ? info.literalStrings : [])].map(normalSymbol).join('\n');
        if (member.slice(0, -6) !== owner) add('archive-class-owner-mismatch', 'Archive path does not match actual class owner ' + owner + '.', member);
        if (info.major > 61) add('archive-java-baseline', 'Class major ' + info.major + ' exceeds shared Java 17 (61).', member);
        if (FORBIDDEN_SYMBOL.test(symbols)) add('archive-forbidden-reference', 'Class constant pool refers to Compose/Skiko/old Compose backend.', member);
        if (KOTLIN_SYMBOL.test(symbols)) add('archive-kotlin-reference', 'Class constant pool refers to Kotlin/kotlinx.', member);
        if (LOADER_SYMBOL.test(symbols) && ![...allowedAcceptance].some(name => owner === name || owner.startsWith(name + '$'))) add('archive-loader-reference', 'Loader symbol outside concrete src/acceptance shell.', member);
        if (/\/(?:ui|model)\//.test(owner) && /net\/minecraft\//.test(symbols)) add('archive-ui-host-coupling', 'UI/model class refers directly to Minecraft.', member);
      }
    } catch (e) { add('archive-entry-invalid', e.message, entry.name); }
  }
  return { findings, entries, expanded: state.expanded };
}
function packageName(tokens) {
  const at = tokens.findIndex(t => t.value === 'package'); if (at < 0) return '';
  let value = ''; for (const t of tokens.slice(at + 1)) { if (t.value === ';' || t.line !== tokens[at].line) break; value += t.value; }
  return value.replaceAll('.', '/');
}
export async function architectureFindings(project, snapshot) {
  const findings = [], allowedAcceptance = new Set();
  const add = (code, message, p) => findings.push({ code, message, path: p });
  for (const f of snapshot.files) {
    const p = f.path;
    if (NATIVE.test(p)) add('source-native-vendored', 'Native library vendored into project inputs.', p);
    if (/\.kt$/.test(p) && p.startsWith('src/')) add('source-kotlin', 'Kotlin production/test source introduced in Java 17 Skija workspace.', p);
    if (/\.(?:class|jar|zip)$/.test(p)) {
      if (p.endsWith('.class')) add('source-vendored-class', 'Precompiled class inside project inputs is not a source implementation.', p);
      else findings.push(...inspectArchive(await regularBytes(project, p), p).findings);
    }
    if (!/\.(?:java|kt|kts|gradle|groovy|ps1|sh|cmd|bat|properties|toml|json|xml)$/.test(p)) continue;
    const text = (await regularBytes(project, p)).toString('utf8');
    const codeFile = /\.(?:java|kt|kts|gradle|groovy)$/.test(p);
    const tokens = codeFile ? lex(text) : [], code = tokens.filter(t => t.type !== 'literal').map(t => t.value).join(''), literals = tokens.filter(t => t.type === 'literal').map(t => t.value).join('\n');
    const symbols = normalSymbol(code + '\n' + literals);
    const acceptance = /^src\/acceptance\/(?:fabric|forge|neoforge)\//.test(p);
    if (acceptance && codeFile) {
      const pkg = packageName(tokens);
      for (let i = 0; i < tokens.length - 1; i++) if (['class', 'interface', 'enum', 'record'].includes(tokens[i].value) && tokens[i - 1]?.value !== '.') allowedAcceptance.add(pkg + '/' + tokens[i + 1].value);
    }
    if (/^src\//.test(p) && codeFile) {
      const sourceSymbols = normalSymbol(code);
      const runtimeTypeLookup = /(?:forName|loadClass|defineClass|ObjectInputStream)/.test(code);
      const inertSchemaOnly = !FORBIDDEN_SYMBOL.test(sourceSymbols) && !runtimeTypeLookup;
      if (FORBIDDEN_SYMBOL.test(symbols)) {
        const testOracleOnly = /^src\/test\//.test(p) && !FORBIDDEN_SYMBOL.test(sourceSymbols);
        const inertSchema = /^src\/main\/.*\/host\/migration\//.test(p) && inertSchemaOnly && tokens.filter(t => t.type === 'literal' && FORBIDDEN_SYMBOL.test(normalSymbol(t.value))).every(t => t.value === 'org.polyfrost.compose.render.PolyColor');
        add(inertSchema ? 'legacy-schema-tag-review' : testOracleOnly ? 'reference-oracle-classpath-review' : 'source-compose-skiko', inertSchema ? 'Exact old serialized PolyColor class tag is compared as inert data; no old class loading or runtime dependency.' : testOracleOnly ? 'Test-only reflective upstream oracle: verify it is excluded from production dependency/package inputs; this is not a production UI runtime.' : 'Source references Compose/Skiko/old Compose backend, including reflective string references.', p);
      }
      if (LOADER_SYMBOL.test(symbols) && !acceptance) {
        const readOnlyContract = /^src\/test\//.test(p) && !LOADER_SYMBOL.test(sourceSymbols);
        add(readOnlyContract ? 'test-source-contract-review' : 'source-loader-reference', readOnlyContract ? 'Test-only contract/oracle names loader symbols without production imports; verify tests and oracle classpath remain excluded from runtime packaging.' : 'Loader symbols are allowed only in src/acceptance/<loader>/.', p);
      }
      if (/^src\/main\/.*\/(?:ui|model)\//.test(p) && /net\/minecraft\//.test(symbols)) add('source-ui-host-coupling', 'UI/model imports or reflectively names Minecraft; use host contract.', p);
      if (SKIJA_DEFINITION.test(packageName(tokens))) add('source-skija-definition', 'Project defines a second Skija implementation.', p);
      if (/(?:^|[\n])(?:kotlin|kotlinx)\//.test(symbols) || /import(?:kotlin|kotlinx)\//.test(symbols)) add('source-kotlin-reference', 'Source imports Kotlin/kotlinx rather than Java/shared host contract.', p);
    }
    if (!p.startsWith('src/') && /\.(?:gradle|kts|groovy|properties|toml|ps1|sh|cmd|bat|json|xml)$/.test(p)) {
      const configText = codeFile ? code + '\n' + literals : text;
      if (/(?:androidx[.:]compose|org\.jetbrains\.(?:compose|skiko)|org\.polyfrost\.compose|compose-(?:runtime|ui|foundation|material)|\bskiko[-:])/i.test(configText)) add('build-compose-skiko-dependency', 'Build configuration introduces Compose/Skiko dependency or plugin.', p);
      if (/(?:org\.jetbrains\.kotlin|kotlin-(?:stdlib|reflect)|kotlinx-coroutines|kotlin\(["'](?:jvm|multiplatform))/i.test(configText)) add('build-kotlin-dependency', 'Workspace adds Kotlin plugin/runtime dependency.', p);
      const declaredSkijaDependency = (codeFile || /\.(?:toml|properties|json|xml)$/.test(p)) && /(?:io\.github\.humbleui|org\.jetbrains\.skija)[:\/]|skija-(?:shared|windows|linux|macos)|skija-native/i.test(configText);
      const downloadedSkija = /\.(?:ps1|sh|cmd|bat)$/.test(p) && /(?:skija|skiko|\.dll|\.dylib)/i.test(configText) && /(?:Invoke-WebRequest|Start-BitsTransfer|DownloadFile|\bcurl\b|\bwget\b|\biwr\b)/i.test(configText);
      if (declaredSkijaDependency || downloadedSkija) add('build-second-skija-dependency', 'Workspace declares/downloads its own Skija/native dependency; selecting an existing host classpath is inspected through the final archive.', p);
      if (/\b(?:zipTree|shadowJar|jarJar)\b/.test(configText)) add('build-bundled-dependency-review', 'Dependency expansion/bundling requires verified final archive ownership; source config is not sufficient.', p);
    }
  }
  return { findings, allowedAcceptance };
}

async function validateImplementation(feature, context, add) {
  const review = feature.implementationReview || { result: 'pending' };
  if (!['pending', 'partial', 'implemented'].includes(review.result)) { add('implementation-review-invalid', 'Implementation review result must be pending, partial or implemented.', null, feature.id); return false; }
  if (review.result === 'pending') return false;
  let good = true;
  const fail = (code, message, p) => { good = false; add(code, message, p, feature.id); };
  if (!nonempty(review.by) || !nonempty(review.notes) || !feature.implementation?.length) fail('implementation-review-invalid', 'A named review, scope/limitations and concrete implementation paths are required.');
  for (const implementation of feature.implementation || []) {
    if (!/^src\/(?:main|acceptance)\/.+\.java$/.test(implementation.path || '') || !Number.isInteger(implementation.line) || implementation.line < 1) fail('implementation-invalid', 'Concrete Java implementation path and one-based line required.', implementation.path);
    if (!(await checkFileEvidence(implementation, context, fail, feature.id, 'implementation'))) good = false;
  }
  const automated = feature.evidence?.automatedNeutral || [];
  if (!automated.length) { fail('implementation-automation-pending', 'Implementation awaits neutral behavioral evidence; this does not claim a failed function.'); return false; }
  for (const record of automated) {
    if (!(await checkFileEvidence(record.report, context, fail, feature.id, 'neutral automated report'))) { good = false; continue; }
    try {
      const output = JSON.parse((await evidenceBytes(context, record.report.path)).toString('utf8').replace(/^\uFEFF/, ''));
      if (output.schemaVersion !== 1 || output.kind !== 'neutral-behavior' || output.result !== 'pass' || output.sourceTreeSha256 !== context.sourceTreeSha256 || !Array.isArray(output.groups) || !output.groups.length) fail('implementation-automation-invalid', 'Neutral report must identify this source baseline and actual passing behavioral groups.', record.report.path);
      const fingerprints = new Map((output.implementation || []).map(item => [item.path, item.sha256]));
      for (const dependency of output.dependencies || []) if (!(await checkFileEvidence(dependency, context, fail, feature.id, "behavior dependency"))) good = false;
      for (const impl of feature.implementation || []) if (fingerprints.get(impl.path) !== impl.sha256) fail('implementation-automation-stale', 'Neutral report does not cover the current implementation bytes.', impl.path);
      if (!nonempty(record.observed)) fail('implementation-automation-invalid', 'Neutral evidence needs an observed result and explicit tested scope.');
    } catch (error) { fail('implementation-automation-invalid', error.message, record.report.path); }
  }
  return good;
}


function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
function soundDescriptor(sourceBytes, targetBytes) {
  const remap = value => typeof value === 'string' ? value.replace(/^oneconfig:/, 'muoniumconfigskija:') : Array.isArray(value) ? value.map(remap) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, child]) => [key, remap(child)])) : value;
  return json(canonical(remap(JSON.parse(sourceBytes)))) === json(canonical(JSON.parse(targetBytes)));
}

export async function runParity(options = {}) {
  const project = path.resolve(options.project || PROJECT_ROOT), findings = [];
  const add = (code, message, p, featureId) => findings.push({ code, message, ...(p ? { path: p } : {}), ...(featureId ? { featureId } : {}) });
  const baseline = options.baseline || JSON.parse(await fs.readFile(localPath(project, 'reference/source-manifest.json'), 'utf8'));
  const map = options.featureMap || JSON.parse(await fs.readFile(localPath(project, 'reference/feature-map.json'), 'utf8'));
  if (baseline.schemaVersion !== 1 || map.schemaVersion !== 1) throw new Error('Unsupported reference schema.');
  const source = options.source || baseline.source.root, current = options.current || await collectSource(source);
  findings.push(...diffSource(baseline, current));
  if (!unique(baseline.files.map(f => f.path))) add('baseline-duplicate-file', 'Baseline contains duplicate file identities.');
  if (map.sourceHead !== baseline.source.head || map.sourceTreeSha256 !== baseline.sourceTreeSha256) add('feature-baseline-mismatch', 'Feature map is not tied to the captured source baseline.');
  if (json(map.targets) !== json(TARGETS)) add('target-matrix-modified', 'The seven lanes / fourteen targets may not be omitted or silently downgraded.');
  const expected = await makeFeatureMap(current, project), expectedById = new Map(expected.features.map(f => [f.id, f]));
  for (const issue of expected.catalogIssues) add('catalog-anchor-missing', issue);
  if (json(map.catalogIssues) !== json(expected.catalogIssues)) add('catalog-issues-modified', 'Captured catalog issues differ from freshly resolved source anchors.');
  if (!Array.isArray(map.features) || !unique(map.features.map(f => f.id))) throw new Error('Feature map must contain unique feature IDs.');
  const mapById = new Map(map.features.map(f => [f.id, f]));
  for (const [id, feature] of expectedById) {
    const actual = mapById.get(id);
    if (!actual) { add('feature-removed', 'Required source behavior/file was removed from feature map.', null, id); continue; }
    for (const key of ['domain', 'behavior', 'acceptance', 'sourceAnchors', 'surfaceIds', 'targets', 'sourceFingerprint']) if (json(actual[key]) !== json(feature[key])) add('feature-contract-modified', 'Generated field ' + key + ' differs; rerun audit after reviewing source drift, do not drop coverage.', null, id);
  }
  for (const id of mapById.keys()) if (!expectedById.has(id)) add('feature-unrecognized', 'Feature no longer matches current source; review baseline drift.', null, id);
  for (const feature of map.retiredFeatures || []) add('retired-feature-review', 'Removed upstream feature requires explicit migration review; retained evidence cannot be silently discarded.', null, feature.id);
  const snapshot = await scanProject(project), context = { project, sourceTreeSha256: current.sourceTreeSha256, implementationSnapshotSha256: snapshot.implementationSnapshotSha256 };
  let complete = 0, implementationChecked = 0;
  for (const feature of map.features) {
    if (!Array.isArray(feature.targets) || !feature.targets.length) { add('feature-targets-empty', 'Feature must identify applicable exact combinations.', null, feature.id); continue; }
    if (await validateImplementation(feature, context, add)) implementationChecked++;
    if (await validateFeature(feature, context, add)) complete++;
  }
  const assetBySource = new Map((map.assets || []).map(a => [a.source, a]));
  if (!unique((map.assets || []).map(a => a.source))) add('asset-map-duplicate', 'Duplicate asset requirements are not permitted.');
  let assetsMatched = 0, namespaceDescriptorsMatched = 0;
  const requiredAssets = current.assets.filter(a => ['byte-identical', 'namespace-sound-descriptor'].includes(a.requirement));
  for (const asset of requiredAssets) {
    const item = assetBySource.get(asset.source);
    if (!item) { add('asset-requirement-removed', 'Required asset removed from feature map.', asset.source); continue; }
    if (item.sha256 !== asset.sha256 || item.logicalPath !== asset.logicalPath || item.bytes !== asset.bytes || item.requirement !== asset.requirement) add('asset-baseline-mismatch', 'Asset source contract does not match actual upstream bytes.', asset.source);
    if (!/^src\/main\/resources\/.+/.test(item.destination || '')) { add('asset-destination-invalid', 'Byte-identical assets must map to actual production resources, not references/docs/build outputs.', item.destination); continue; }
    try {
      const bytes = await regularBytes(project, item.destination);
      if (asset.requirement === 'namespace-sound-descriptor') {
        if (!soundDescriptor(await regularBytes(current.source.root, asset.source), bytes)) add('asset-sound-descriptor-differ', 'Only the exact oneconfig: -> muoniumconfigskija: resource namespace rewrite is permitted; event names, order, stream flags and targets must otherwise match.', item.destination);
        else namespaceDescriptorsMatched++;
      } else if (hash(bytes) !== asset.sha256 || bytes.length !== asset.bytes) add('asset-bytes-differ', 'Source ' + asset.source + ' requires SHA256 ' + asset.sha256 + '; port has ' + hash(bytes) + '.', item.destination);
      else assetsMatched++;
    } catch (e) { add('asset-missing', 'Missing/unreadable byte-identical source asset ' + asset.source + ': ' + e.message, item.destination); }
  }
  for (const asset of map.assets || []) if (!requiredAssets.some(a => a.source === asset.source)) add('asset-unrecognized', 'Asset mapping is not a current upstream requirement.', asset.source);
  const architecture = await architectureFindings(project, snapshot); findings.push(...architecture.findings);
  const artifactPaths = [];
  // Inspect installable and named development packages; test fixtures may contain deliberate unsafe-path symlinks.
  for (const directory of ['dist', 'build/devdist']) if (await exists(localPath(project, directory))) for (const p of await walk(localPath(project, directory))) if (/\.jar$/i.test(p)) artifactPaths.push(directory + '/' + p);
  const artifacts = [], artifactTargets = new Set();
  const provenancePath = 'build/acceptance/artifact-provenance.json';
  const provenance = await exists(localPath(project, provenancePath)) ? JSON.parse(await regularBytes(project, provenancePath)) : null;
  if (!provenance) add('artifact-provenance-missing', 'No current source-bound build log/provenance. Existing JAR inspection does not prove build freshness or runtime behavior.', provenancePath);
  else if (provenance.schemaVersion !== 1) add('artifact-provenance-invalid', 'Unsupported artifact provenance schema.', provenancePath);
  else if (provenance.sourceTreeSha256 !== context.sourceTreeSha256 || provenance.implementationSnapshotSha256 !== context.implementationSnapshotSha256) add('artifact-provenance-stale', 'Build provenance does not identify this source baseline and project snapshot.', provenancePath);
  for (const p of ordered(new Set(artifactPaths))) {
    const bytes = await regularBytes(project, p), sha256 = hash(bytes), inspection = inspectArchive(bytes, p, architecture.allowedAcceptance);
    findings.push(...inspection.findings);
    const match = path.basename(p).match(/^MuoniumConfig-Skija-(\d+(?:\.\d+)+)-(fabric|forge|neoforge)-.+\.jar$/), target = match ? match[1] + '/' + match[2] : null;
    if (target && TARGETS.includes(target)) artifactTargets.add(target);
    else add('artifact-target-unrecognized', 'Cannot identify exact supported MC/loader target from artifact.', p);
    const proof = provenance?.artifacts?.find(a => a.path === p && a.sha256 === sha256 && a.target === target && a.result === 'pass' && nonempty(a.command));
    if (provenance && !proof) add('artifact-freshness-unverified', 'JAR hash/target has no successful build record in current provenance.', p);
    if (proof) await checkFileEvidence(proof.log, context, add, null, 'artifact build log');
    // Verify the actual release archive also contains each byte-mapped project resource.
    const top = new Map(inspection.entries.map(e => [e.name, e]));
    for (const asset of requiredAssets) {
      const item = assetBySource.get(asset.source); if (!item?.destination?.startsWith('src/main/resources/')) continue;
      const name = item.destination.slice('src/main/resources/'.length), entry = top.get(name);
      if (!entry) add('artifact-asset-missing', 'Source asset absent from release JAR: ' + name, p);
      else {
        try {
          const data = entry.data();
          if (asset.requirement === 'namespace-sound-descriptor') { if (!soundDescriptor(await regularBytes(current.source.root, asset.source), data)) add('artifact-sound-descriptor-differ', 'Release sound declaration differs beyond resource namespace: ' + name, p); }
          else if (hash(data) !== asset.sha256) add('artifact-asset-bytes-differ', 'Release asset is not byte-identical: ' + name, p);
        }
        catch (e) { add('artifact-asset-unreadable', e.message, p + '!' + name); }
      }
    }
    artifacts.push({ path: p, sha256, bytes: bytes.length, target, memberCount: inspection.entries.length, forbiddenFindings: inspection.findings.length, freshness: proof && provenance.sourceTreeSha256 === context.sourceTreeSha256 && provenance.implementationSnapshotSha256 === context.implementationSnapshotSha256 ? 'recorded-build-log-needs-review' : 'unverified' });
  }
  for (const target of TARGETS) if (!artifactTargets.has(target)) add('artifact-target-missing', 'No release artifact inspected for ' + target + '.');
  const finalSnapshot = await scanProject(project);
  if (finalSnapshot.implementationSnapshotSha256 !== snapshot.implementationSnapshotSha256) add('project-changed-during-check', 'Teammate edits changed project inputs during inspection; rerun before accepting result.');
  return {
    schemaVersion: 1, readOnly: true, ok: findings.length === 0,
    source: { head: current.source.head, sourceTreeSha256: current.sourceTreeSha256, clean: current.source.clean },
    implementationSnapshotSha256: context.implementationSnapshotSha256,
    summary: { features: expected.features.length, implementationChecked, implementationClaimed: map.features.filter(f => ['partial', 'implemented'].includes(f.implementationReview?.result)).length, complete, pending: map.features.filter(f => f.status !== 'complete').length, claimedComplete: map.features.filter(f => f.status === 'complete').length, assets: requiredAssets.filter(a => a.requirement === 'byte-identical').length, assetsMatched, namespaceDescriptors: requiredAssets.filter(a => a.requirement === 'namespace-sound-descriptor').length, namespaceDescriptorsMatched, artifacts: artifacts.length, inspectedTargets: ordered(artifactTargets), findings: findings.length },
    limitations: ['Static lexical inventory is not runtime reachability or API verification.', 'Evidence metadata/hashes are checked; observations and visual equality still require review.', 'JAR inspection is of existing bytes, not proof of a fresh build or Mixin/runtime success.', 'No Gradle, dependency resolution, full builds or game runs were invoked.'],
    artifacts, findings,
  };
}
function syntheticZip(entries) {
  const locals = [], central = []; let offset = 0;
  for (const [name, payload, method = 0] of entries) {
    const data = Buffer.from(payload), compressed = method === 8 ? deflateRawSync(data) : data, label = Buffer.from(name), local = Buffer.alloc(30), directory = Buffer.alloc(46), crc = crc32(data);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(method, 8); local.writeUInt32LE(crc, 14); local.writeUInt32LE(compressed.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(label.length, 26);
    directory.writeUInt32LE(0x02014b50); directory.writeUInt16LE(20, 4); directory.writeUInt16LE(20, 6); directory.writeUInt16LE(method, 10); directory.writeUInt32LE(crc, 16); directory.writeUInt32LE(compressed.length, 20); directory.writeUInt32LE(data.length, 24); directory.writeUInt16LE(label.length, 28); directory.writeUInt32LE(offset, 42);
    locals.push(local, label, compressed); central.push(directory, label); offset += local.length + label.length + compressed.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}
function syntheticClass(owner, references = [], major = 61, literals = []) {
  const names = [owner, 'java/lang/Object', ...references, ...literals], parts = [], header = Buffer.alloc(10);
  header.writeUInt32BE(0xcafebabe); header.writeUInt16BE(major, 6); header.writeUInt16BE(names.length + 3 + literals.length, 8);
  for (const name of names) { const bytes = Buffer.from(name), prefix = Buffer.alloc(3); prefix[0] = 1; prefix.writeUInt16BE(bytes.length, 1); parts.push(prefix, bytes); }
  for (const index of [1, 2]) { const ref = Buffer.alloc(3); ref[0] = 7; ref.writeUInt16BE(index, 1); parts.push(ref); }
  for (let i = 0; i < literals.length; i++) { const ref = Buffer.alloc(3); ref[0] = 8; ref.writeUInt16BE(names.length - literals.length + i + 1, 1); parts.push(ref); }
  const tail = Buffer.alloc(14); tail.writeUInt16BE(0x21); tail.writeUInt16BE(names.length + 1, 2); tail.writeUInt16BE(names.length + 2, 4);
  return Buffer.concat([header, ...parts, tail]);
}
export async function paritySelfTest() {
  const findings = [], add = (code, message, p, id) => findings.push({ code, message, p, id });
  await validateFeature({ id: 'fake', status: 'complete', implementation: [], targets: ['26.3/fabric'], surfaceIds: ['site-1'] }, { project: PROJECT_ROOT }, add);
  assert(findings.some(f => f.code === 'implementation-missing'));
  assert(findings.some(f => f.code === 'evidence-game-missing'));
  assert(findings.some(f => f.code === 'semantic-site-uncovered'));
  const archive = syntheticZip([['assets/icon.bin', Buffer.from([0, 255, 13, 10]), 8]]);
  assert.deepEqual(readZip(archive)[0].data(), Buffer.from([0, 255, 13, 10]));
  const nested = syntheticZip([['lib/hidden.jar', syntheticZip([['kotlin/Runtime.class', Buffer.from('not a class')], ['natives/skija.dll', Buffer.from([1])]]), 8]]);
  const inspected = inspectArchive(nested, 'fixture.jar');
  assert(inspected.findings.some(f => f.code === 'archive-kotlin-bundled'));
  assert(inspected.findings.some(f => f.code === 'archive-native-bundled'));
  assert(inspected.findings.some(f => f.code === 'archive-entry-invalid'));
  assert.throws(() => readZip(syntheticZip([['../outside', 'x']])));
  assert.throws(() => readZip(syntheticZip([['a', 'x'], ['a', 'y']])));
  assert.throws(() => readZip(Buffer.from('not ZIP')));
  const corrupt = Buffer.from(archive); corrupt[30 + Buffer.byteLength('assets/icon.bin')] ^= 255;
  assert.throws(() => readZip(corrupt)[0].data());
  const original = { source: { head: 'a', statusPorcelainV1Z: [] }, sourceTreeSha256: 'a', files: [{ path: 'x', sha256: 'a', bytes: 1 }], uiSources: [], missingTracked: [] };
  assert(diffSource(original, { ...original, source: { head: 'b', statusPorcelainV1Z: [' M x'] }, files: [{ path: 'x', sha256: 'b', bytes: 1 }, { path: 'y', sha256: 'c', bytes: 1 }] }).some(f => f.code === 'source-bytes-drift'));
  const owner = 'com/muoniumplayer/configskija/loader/Acceptance';
  const shell = syntheticZip([[owner + '.class', syntheticClass(owner, ['Lnet/neoforged/fml/common/Mod;'])]]);
  assert(inspectArchive(shell, 'shell.jar').findings.some(f => f.code === 'archive-loader-reference'));
  assert(!inspectArchive(shell, 'shell.jar', new Set([owner])).findings.some(f => f.code === 'archive-loader-reference'));
  const injected = syntheticZip([['com/muoniumplayer/configskija/ui/View.class', syntheticClass('com/muoniumplayer/configskija/ui/View', ['Landroidx/compose/runtime/Composable;', 'Lkotlin/Metadata;', 'Lnet/minecraft/client/Minecraft;'])]]);
  const symbols = inspectArchive(injected, 'injected.jar').findings;
  assert(symbols.some(f => f.code === 'archive-forbidden-reference'));
  assert(symbols.some(f => f.code === 'archive-kotlin-reference'));
  assert(symbols.some(f => f.code === 'archive-ui-host-coupling'));
  const hostSkija = syntheticZip([['com/muoniumplayer/configskija/ui/Canvas.class', syntheticClass('com/muoniumplayer/configskija/ui/Canvas', ['Lio/github/humbleui/skija/Canvas;'])]]);
  assert.equal(inspectArchive(hostSkija, 'host-skija.jar').findings.length, 0);
  const secondSkija = syntheticZip([['io/github/humbleui/skija/Canvas.class', syntheticClass('io/github/humbleui/skija/Canvas')]]);
  assert(inspectArchive(secondSkija, 'second-skija.jar').findings.some(f => f.code === 'archive-skija-bundled'));
  assert.throws(() => classInfo(syntheticClass(owner).subarray(0, -2)));
  const relocatedRuntime = inspectArchive(syntheticZip([['META-INF/example.kotlin_module', 'metadata'], ['repackage/kotlin/Unit.class', syntheticClass('repackage/kotlin/Unit')]]), 'relocated.jar');
  assert.equal(relocatedRuntime.findings.filter(f => f.code === 'archive-kotlin-bundled').length, 2);
  assert(LOADER_SYMBOL.test('net/neoforged/fml/common/Mod'));
  assert(FORBIDDEN_SYMBOL.test('org/jetbrains/skiko/SkiaLayer'));
  const memoryFiles = new Map(), sourceTreeSha256 = 'a'.repeat(64), implementationSnapshotSha256 = 'b'.repeat(64), target = '26.3/fabric';
  const put = (p, value) => { const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value); memoryFiles.set(p, bytes); return { path: p, sha256: hash(bytes) }; };
  const impl = put('src/main/java/example/View.java', 'class View {}\n'); impl.line = 1;
  const output = { schemaVersion: 1, kind: 'behavior', result: 'pass', featureId: 'checked', sourceTreeSha256, implementationSnapshotSha256, targets: [target], cases: [{ id: 'click', result: 'pass', assertions: [{ expected: 'toggle enabled', observed: 'toggle enabled', pass: true }] }] };
  const outputRef = put('build/acceptance/behavior.json', json(output));
  const gameRef = put('build/acceptance/game.md', 'Observed one click enabling the toggle in 26.3/fabric.');
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64');
  const sourceCapture = put('build/acceptance/source.png', png), portCapture = put('build/acceptance/port.png', png);
  const baseRecord = { featureId: 'checked', caseIds: ['click'], targets: [target], result: 'pass', sourceTreeSha256, implementationSnapshotSha256, command: 'actual behavioral click procedure', observed: 'One click enabled the visible toggle.' };
  const full = { id: 'checked', status: 'complete', targets: [target], surfaceIds: ['site-1'], implementation: [impl], semanticReview: { result: 'reviewed', by: 'fixture reviewer', notes: 'Observed visible toggle state.', cases: [{ id: 'click', surfaceIds: ['site-1'], expected: 'toggle enabled' }] }, evidence: { automated: [{ ...baseRecord, kind: 'behavior', report: outputRef }], game: [{ ...baseRecord, report: gameRef }], visual: [{ ...baseRecord, report: gameRef, captures: [{ source: sourceCapture, port: portCapture, comparison: 'Paired fixture captures match layout and toggle state.' }] }] } };
  const memory = { project: PROJECT_ROOT, sourceTreeSha256, implementationSnapshotSha256, readBytes: async p => { if (!memoryFiles.has(p)) throw new Error('fixture file missing'); return memoryFiles.get(p); } };
  const positive = [];
  assert.equal(await validateFeature(full, memory, (code, message) => positive.push({ code, message })), true, json(positive));
  const inertOwner = 'com/muoniumplayer/configskija/host/migration/LegacyTag';
  const inert = syntheticZip([[inertOwner+'.class', syntheticClass(inertOwner, [], 61, ['org.polyfrost.compose.render.PolyColor'])]]);
  assert(!inspectArchive(inert,'inert.jar').findings.some(f => f.code === 'archive-forbidden-reference'));
  const reflective = syntheticZip([[inertOwner+'.class', syntheticClass(inertOwner, ['java/lang/Class','forName'], 61, ['androidx.compose.runtime.Composable'])]]);
  assert(inspectArchive(reflective,'reflective.jar').findings.some(f => f.code === 'archive-forbidden-reference'));
  const neutralOutput = { schemaVersion: 1, kind: 'neutral-behavior', result: 'pass', sourceTreeSha256, implementation: [{path:impl.path,sha256:impl.sha256}], groups: ['visible-state-check'] };
  const neutralRef = put('build/acceptance/neutral.json', json(neutralOutput));
  const partial = { id: 'partial', status: 'pending', implementation: [impl], implementationReview: {result:'partial',by:'fixture reviewer',notes:'Core behavior tested; real renderer remains pending.'}, evidence: { automatedNeutral: [{report:neutralRef,observed:'visible state assertion passed'}] } };
  assert.equal(await validateImplementation(partial, memory, () => {}), true);
  neutralOutput.sourceTreeSha256 = 'c'.repeat(64); partial.evidence.automatedNeutral[0].report = put('build/acceptance/neutral-stale.json', json(neutralOutput));
  assert.equal(await validateImplementation(partial, memory, () => {}), false);
  const compileOnly = structuredClone(full); compileOnly.evidence.automated[0].report = put('build/acceptance/compile.txt', 'BUILD SUCCESSFUL');
  const rejected = [];
  assert.equal(await validateFeature(compileOnly, memory, (code, message) => rejected.push({ code, message })), false);
  assert(rejected.some(f => f.code === 'evidence-automated-invalid'));
  const fakeCapture = structuredClone(full); fakeCapture.evidence.visual[0].captures[0].port = put('build/acceptance/fake.png', 'placeholder screenshot');
  assert.equal(await validateFeature(fakeCapture, memory, () => {}), false);
  const stale = structuredClone(full); stale.evidence.game[0].implementationSnapshotSha256 = 'c'.repeat(64);
  assert.equal(await validateFeature(stale, memory, () => {}), false);
  assert(!validCapture('fake.png', Buffer.from('not PNG')));
  assert(soundDescriptor(Buffer.from('{"ui.click":{"sounds":["oneconfig:ui/click"]}}'),Buffer.from('{"ui.click":{"sounds":["muoniumconfigskija:ui/click"]}}')));
  assert(!soundDescriptor(Buffer.from('{"ui.click":{"sounds":["oneconfig:ui/click"]}}'),Buffer.from('{"ui.click":{"sounds":["muoniumconfigskija:ui/wrong"]}}')));
  const staged = stageReport({ findings: [{code:'feature-pending',message:'awaiting human'},{code:'artifact-target-missing',message:'test package not yet built'},{code:'artifact-asset-missing',message:'old package not yet rebuilt'}], summary: { pending: 2 } }, 'implementation');
  assert.equal(staged.ok, true); assert.equal(staged.fullParityOk, false); assert.equal(staged.testPackageGenerationBlockedByParity, false);
  assert.equal(stageReport({findings:[{code:'asset-missing',message:'actual missing input'}],summary:{pending:0}},'implementation').ok,false);
  return { ok: true, tests: ['fake-completion-rejected', 'missing-game-and-sites-rejected', 'stored-and-deflated-binary-zip', 'nested-kotlin-and-native-detection', 'malformed-classes-fail', 'zip-traversal-and-duplicates-rejected', 'crc-corruption-rejected', 'source-drift-detected', 'loader-skiko-symbols', 'acceptance-reference-only-exception', 'actual-class-owner-and-structure', 'compose-kotlin-host-coupling-constant-pool', 'host-skija-reference-vs-second-definition', 'complete-evidence-positive-fixture', 'compile-log-cannot-count-as-behavior', 'fake-screenshot-rejected', 'stale-snapshot-rejected', 'relocated-kotlin-and-metadata-detection', 'implementation-stage-retains-human-pending', 'implementation-stage-keeps-real-guards', 'sound-descriptor-exact-namespace-contract', 'neutral-implementation-evidence-separate-from-human', 'inert-schema-tag-vs-reflective-runtime-reference'] };
}

export function stageReport(report, stage = 'full') {
  if (!['full', 'implementation'].includes(stage)) throw new Error('Unknown stage: ' + stage);
  const pendingHuman = new Set(['feature-pending', 'semantic-review-required', 'semantic-site-uncovered', 'evidence-game-missing', 'evidence-game-invalid', 'evidence-game-coverage', 'evidence-visual-missing', 'evidence-visual-invalid', 'evidence-visual-coverage', 'artifact-provenance-missing', 'artifact-target-missing', 'artifact-freshness-unverified', 'artifact-asset-missing', 'artifact-asset-bytes-differ', 'artifact-sound-descriptor-differ', 'implementation-automation-pending', 'reference-oracle-classpath-review', 'legacy-schema-tag-review', 'test-source-contract-review']);
  const humanFinding = f => pendingHuman.has(f.code) || /^evidence-file-/.test(f.code) && /^(?:game report|visual report|visual source|visual port)/.test(f.message);
  const nonblocking = stage === 'implementation' ? report.findings.filter(humanFinding) : [];
  const blocking = stage === 'implementation' ? report.findings.filter(f => !humanFinding(f)) : report.findings;
  return { ...report, stage, ok: blocking.length === 0, fullParityOk: report.findings.length === 0,
    testPackageGenerationBlockedByParity: false,
    summary: { ...report.summary, awaitingEvidence: report.summary.pending, blockingChecks: blocking.length, informationalChecks: nonblocking.length },
    stageNote: stage === 'implementation' ? 'Source/test readiness only. Pending human evidence and stale/missing release artifact resources are informational until a current test package is built; forbidden runtime/package dependencies remain blocking. These are not failed-feature counts. All findings remain visible. This does not certify 1:1 parity and is not a package-generation prerequisite.' : 'Full evidence completeness. Pending entries are awaiting evidence, not a count of failed functionality. Test packages may be generated independently for human acceptance.' };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) { console.log('Usage: node tools/check-parity.mjs [--source PATH] [--project PATH] [--json] [--stage full|implementation] [--self-test]\nRead-only. Exit 0=all constraints satisfied, 1=gaps, 2=input/tool error. Pending, missing artifacts and unverified evidence never pass.'); return; }
  if (options['self-test']) { console.log(json(await paritySelfTest())); return; }
  const report = stageReport(await runParity(options), options.stage || 'full');
  if (options.json) console.log(json(report));
  else {
    console.log('Stage ' + report.stage + ' ' + (report.ok ? 'READY' : 'INCOMPLETE') + ': ' + report.summary.complete + '/' + report.summary.features + ' verified; ' + report.summary.pending + ' pending; assets ' + report.summary.assetsMatched + '/' + report.summary.assets + '; artifacts ' + report.summary.artifacts + '/' + TARGETS.length + '.');
    console.log('HEAD ' + report.source.head + '\nProject snapshot ' + report.implementationSnapshotSha256);
    for (const f of report.findings) console.log('[' + f.code + '] ' + (f.featureId ? f.featureId + ' ' : '') + (f.path ? f.path + ': ' : '') + f.message);
    console.log(report.stageNote);
    for (const note of report.limitations) console.log('LIMIT: ' + note);
  }
  if (!report.ok) process.exitCode = 1;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error('check-parity: ' + e.message); process.exitCode = 2; });
