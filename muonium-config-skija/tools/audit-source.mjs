#!/usr/bin/env node
/** Read-only upstream inventory. --write updates only the three named local reference files. */
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

export const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const DEFAULT_SOURCE = 'E:/oneconfig lite/MuoniumConfig-src';
export const POLICY_DATE = '2026-11-24';
export const LANES = ['1.20.1', '1.21.1', '1.21.10', '1.21.11', '26.1.2', '26.2', '26.3'];
export const TARGETS = LANES.flatMap(mc => ['fabric', mc === '1.20.1' ? 'forge' : 'neoforge'].map(loader => mc + '/' + loader));
const SKIP_DIRS = new Set(['.git', '.gradle', '.idea', '.kotlin', 'build', 'dist', 'run', 'node_modules', '__pycache__', '.recover-backup', '_trash', '.scratch', '_scratch']);
export const slash = p => p.replaceAll('\\', '/');
export const hash = value => createHash('sha256').update(value).digest('hex');
export const json = value => JSON.stringify(value, null, 2) + '\n';
export const ordered = values => [...values].sort((a, b) => a < b ? -1 : a > b ? 1 : 0);
export const exists = async p => fs.access(p).then(() => true, e => { if (e.code === 'ENOENT') return false; throw e; });
export function localPath(root, relative) {
  if (typeof relative !== 'string' || !relative || relative.includes('\\') || path.isAbsolute(relative) || /^[A-Za-z]:/.test(relative)) throw new Error('Expected a relative slash-separated path: ' + relative);
  const resolved = path.resolve(root, relative);
  const rel = path.relative(path.resolve(root), resolved);
  if (rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel)) throw new Error('Path escapes root: ' + relative);
  return resolved;
}
export async function regularBytes(root, relative) {
  const full = localPath(root, relative);
  const realRoot = await fs.realpath(root);
  const real = await fs.realpath(full);
  const rel = path.relative(realRoot, real);
  if (rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel)) throw new Error('Symlink escapes root: ' + relative);
  const stat = await fs.lstat(full);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Not a regular file: ' + relative);
  return fs.readFile(full);
}
export async function walk(root, { exclude = SKIP_DIRS } = {}) {
  const result = [];
  async function visit(directory, relative = '') {
    for (const item of await fs.readdir(directory, { withFileTypes: true })) {
      const rel = relative ? relative + '/' + item.name : item.name;
      if (item.isSymbolicLink()) throw new Error('Unreviewed symbolic link: ' + rel);
      if (item.isDirectory()) {
        if (!exclude.has(item.name) && !/^production-test(?:-|$)/.test(item.name)) await visit(path.join(directory, item.name), rel);
      } else if (item.isFile()) result.push(rel);
    }
  }
  await visit(root);
  return ordered(result);
}
function git(root, ...args) {
  return execFileSync('git', ['-c', 'safe.directory=' + slash(path.resolve(root)), '-C', root, ...args], {
    encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true,
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
  });
}
function gitState(root) {
  return {
    head: git(root, 'rev-parse', 'HEAD').trim(),
    branch: git(root, 'rev-parse', '--abbrev-ref', 'HEAD').trim(),
    statusPorcelainV1Z: git(root, 'status', '--porcelain=v1', '--untracked-files=all', '-z').split('\0').filter(Boolean),
  };
}
/** Small lexer, not a Kotlin parser: comments and literal contents cannot manufacture API sites. */
export function lex(text) {
  const tokens = [];
  let i = 0, line = 1;
  const push = (type, value, start, at) => tokens.push({ type, value, offset: start, line: at });
  while (i < text.length) {
    const ch = text[i], start = i, at = line;
    if (/\s/.test(ch)) { if (ch === '\n') line++; i++; continue; }
    if (text.startsWith('//', i)) { while (i < text.length && text[i] !== '\n') i++; continue; }
    if (text.startsWith('/*', i)) {
      let depth = 1; i += 2;
      while (i < text.length && depth) {
        if (text.startsWith('/*', i)) { depth++; i += 2; }
        else if (text.startsWith('*/', i)) { depth--; i += 2; }
        else { if (text[i] === '\n') line++; i++; }
      }
      if (depth) throw new Error('Unterminated block comment at line ' + at);
      continue;
    }
    if (ch === '"' || ch === "'") {
      const triple = ch === '"' && text.startsWith('"""', i), quote = triple ? '"""' : ch;
      i += quote.length; let value = '', closed = false;
      while (i < text.length) {
        if (text.startsWith(quote, i)) {
          if (triple) { let run = 3; while (text[i + run] === '"') run++; value += '"'.repeat(run - 3); i += run; }
          else i += quote.length;
          closed = true; break;
        }
        if (!triple && text[i] === '\\') { value += text.slice(i, i + 2); i += 2; continue; }
        if (text[i] === '\n') line++;
        value += text[i++];
      }
      if (!closed) throw new Error('Unterminated literal at line ' + at);
      push('literal', value, start, at); continue;
    }
    if (ch.charCodeAt(0) === 96) {
      i++; let value = '';
      while (i < text.length && text[i].charCodeAt(0) !== 96) value += text[i++];
      if (i === text.length) throw new Error('Unterminated identifier at line ' + at);
      i++; push('id', value, start, at); continue;
    }
    if (/[A-Za-z_$\u0080-\uffff]/.test(ch)) {
      i++; while (i < text.length && /[\w$\u0080-\uffff]/.test(text[i])) i++;
      push('id', text.slice(start, i), start, at); continue;
    }
    if (/\d/.test(ch)) { i++; while (i < text.length && /[\w.]/.test(text[i])) i++; push('number', text.slice(start, i), start, at); continue; }
    i++; push('punct', ch, start, at);
  }
  return tokens;
}
export function domainOf(p) {
  if (/notifications|NotificationsCenter|Toast/.test(p)) return 'notifications';
  if (/HudTour/.test(p)) return 'hud-tour';
  if (/\/hud\/|HudEditor|HudDesign/.test(p)) return 'hud-editor';
  if (/Color|PolyColor/.test(p)) return 'color-gradient';
  if (/Font|\/font\//.test(p)) return 'font-language';
  if (/\/sound\/|sounds(?:\/|\.json)/.test(p)) return 'sound';
  if (/Blur|blur/.test(p)) return 'blur';
  if (/\/search\/|SearchResults/.test(p)) return 'search';
  if (/\/keybind\/|Keybind/.test(p)) return 'keybind';
  if (/preset/i.test(p)) return 'presets';
  if (/Profiles|ProfileData/.test(p)) return 'profiles';
  if (/Themes|\/themes\//.test(p)) return 'theme';
  if (/\/navigation\/|\/page\/|\/shell\/|Header|Sidebar|OneConfigUI|OneConfigInterface/.test(p)) return 'shell-navigation';
  if (/\/settings\/|ConfigScreen|ConfigRegistry|Visualizer/.test(p)) return 'settings';
  return 'ui-support';
}
function uiScope(p) {
  return /\/(?:internal\/ui|api\/(?:ui|hud|notifications)|internal\/presets)\//.test(p) || /\/internal\/OneConfigConfig\.java$/.test(p) || /\/org\/polyfrost\/compose\/(?:font\/|render\/(?:Font|PolyColor|PolyFont|Mc))/.test(p) || /(?:Mixin_UiBlur|BlurScreen|FontLoader)\./.test(p);
}
export function scanSurfaces(p, text) {
  const tokens = lex(text), lines = text.split(/\r?\n/), sites = [], seen = new Set(), occurrences = new Map();
  const aliases = new Map();
  for (const line of lines) { const imported = line.match(/^\s*import\s+([\w.]+)\s+as\s+(\w+)/); if (imported) aliases.set(imported[2], imported[1].split('.').at(-1)); }
  const add = (kind, token, label, role = 'reference') => {
    const key = kind + ':' + token.offset;
    if (seen.has(key)) return; seen.add(key);
    const identity = kind + ':' + label;
    const occurrence = (occurrences.get(identity) || 0) + 1; occurrences.set(identity, occurrence);
    sites.push({ id: 'site-' + hash(p + '\0' + identity + '\0' + occurrence).slice(0, 20), kind, line: token.line, label, role, excerpt: lines[token.line - 1].trim().slice(0, 260) });
  };
  for (let n = 0; n < tokens.length; n++) {
    const token = tokens[n]; if (token.type !== 'id') continue;
    const name = aliases.get(token.value) || token.value, next = tokens[n + 1]?.value, prev = tokens[n - 1]?.value;
    if (/^\s*(?:import|package)\s/.test(lines[token.line - 1])) continue;
    const call = next === '(' || next === '<';
    const label = tokens[n + 1]?.value === '.' && tokens[n + 2]?.type === 'id' ? name + '.' + tokens[n + 2].value : name;
    let annotationAt = n - 1;
    while (tokens[annotationAt]?.value === '.' && tokens[annotationAt - 1]?.type === 'id') annotationAt -= 2;
    if (name === 'Composable' && tokens[annotationAt]?.value === '@') {
      const near = tokens.slice(n + 1, n + 70), fn = near.findIndex(t => t.value === 'fun');
      let declared = null;
      if (fn >= 0 && !near.slice(0, fn).some(t => ['{', '}', '=', ';'].includes(t.value))) {
        const tail = near.slice(fn + 1), bracket = tail.findIndex(t => t.value === '(');
        declared = tail.slice(0, bracket).filter(t => t.type === 'id').at(-1)?.value;
      }
      add('composable', token, declared || '@Composable callback/type', declared ? 'declaration' : 'annotation-or-function-type');
    }
    if (name === 'fun' && uiScope(p)) {
      const tail = tokens.slice(n + 1, n + 80), bracket = tail.findIndex(t => t.value === '(');
      const declared = bracket >= 0 ? tail.slice(0, bracket).filter(t => t.type === 'id').at(-1)?.value : null;
      if (declared) add(/\/screens\//.test(p) ? 'page-or-page-helper' : 'ui-function', token, declared, 'declaration');
    }
    if (uiScope(p) && /^(?:public|val|var)$/.test(name) && /OneConfigConfig\.java$/.test(p) && name === 'public' && tokens[n + 1]?.value === 'static' && tokens[n + 2]?.value !== 'final') {
      const tail = tokens.slice(n + 2, n + 30), eq = tail.findIndex(t => ['=', '(', ';'].includes(t.value));
      const field = tail.slice(0, eq).filter(t => t.type === 'id').at(-1);
      if (field && tail[eq]?.value !== '(') add('preference-setting', token, field.value, 'declaration');
    }
    if (call && /^(?:composable|navigation|BuiltinPage|PageRoute|OneConfigPage|NavigationRoute|ModConfigTarget|pageDestinations|modsGraph|themesGraph|profilesGraph|creditsGraph|preferencesGraph|resolveOpenTarget|registerPage)$/.test(name)) add('route', token, label, 'registration-or-call');
    if (call && /(?:Dialog|Popup|Menu|ContextMenu|Picker)$/.test(name)) add(/Menu$/.test(name) ? 'menu' : 'dialog-or-popup', token, label, prev === 'fun' ? 'declaration' : 'call');
    if (uiScope(p) && ['class', 'object'].includes(prev) && /(?:Graph|Route|Page|Screen)$/.test(name)) add('route', token, name, 'declaration');
    if (/UiSoundEvent\.kt$/.test(p) && /^[A-Z][A-Z_]+$/.test(name) && [',', ';', '}'].includes(next)) add('sound-event', token, name, 'declaration');
    if (/HudTour\.kt$/.test(p) && ['Welcome', 'Adding', 'Dragging', 'Merging', 'Anchoring', 'Settings', 'Designer', 'Fonts', 'Done'].includes(name) && next === '(') add('tour-step', token, name, 'declaration');
    if (/^(?:OneConfigKeybind|ConfigKeybind|KeybindOption|KeybindRecordingBus|KeybindManager|TextInputFocus|onPreviewKeyEvent|onKeyEvent|keyboardActions|isCtrlPressed|isShiftPressed|isAltPressed|isMetaPressed)$/.test(name) || /^GLFW_KEY_/.test(name) || /^getKey[A-Z]/.test(name) || (name === 'Key' && next === '.')) add('keybind', token, label);
    if (/^(?:animate[A-Z]\w*|Animated\w*|Animatable|rememberInfiniteTransition|rememberTransition|updateTransition|tween|spring|keyframes|withFrameNanos|withFrameMillis|RetainedVisibility|ShellTransitions|guiOpenAnimation|guiClosingAnimation|showOpeningPageAnimation|showPageAnimations|animationTime|pageAnimationDuration|marqueeSelectionAnim\w*)$/.test(name)) add('animation', token, label);
    if (/^(?:UiSounds|UiSoundEvent|UiSoundTheme|UiSoundService|McUiSoundService|playSound|acquireAmbience|releaseAmbience|refreshAmbience|startAmbience|stopAmbience|enableUIMusicDucking)$/.test(name)) add('sound', token, label);
    if (/^(?:UiBlur|GlBlur|BlurScreen|enableBackgroundBlur|backgroundBlur|blurBackground)$/.test(name)) add('blur', token, label);
    if (/^(?:UiFontOverride|UiFontFiles|UiFontFamilies|McFontService|McResourceFont|I18nPlatform|Language|setLanguage|setLocale|localizedStringState)$/.test(name)) add('font-language', token, label);
    if (/^(?:SearchProvider|SearchProviders|SearchProviderRegistry|registerSearchProvider|DefaultSearchProvider|PageSearchQuery|SearchScope|SearchCorpus)$/.test(name)) add('search-provider', token, label);
    if (/^(?:NotificationToasts|NotificationsCenter|ToastInput|NotificationsManager|HudTourOverlay|HudTourStep|HudLibraryPanel|HudLibraryHeader|anchorTo|clearAnchor|pinCorner|setGrowthAnchorKeepingPosition|setSelfAnchorPointKeepingPosition|HudBackgroundMerge)$/.test(name)) add('peripheral-ui', token, label);
  }
  return sites.sort((a, b) => a.line - b.line || a.kind.localeCompare(b.kind));
}
function kindOf(p) {
  if (/\/src\/test\//.test(p)) return 'test';
  if (/\.(?:kt|java|kts)$/.test(p) && /\/src\//.test(p)) return 'source';
  if (/\/src\/[^/]*\/resources\//.test(p)) return 'resource';
  if (/(?:\.gradle(?:\.kts)?|\.properties|\.toml|\.classtweaker|\.cfg)$/.test(p) || p.startsWith('gradle/')) return 'build-contract';
  if (/\.md$/.test(p)) return 'documentation';
  return 'repository-support';
}

/** Fingerprint build/source assets only. Runtime configs and secret containers are never read. */
export function fingerprintInput(p) {
  const parts = p.split('/');
  if (parts.some(part => SKIP_DIRS.has(part) || /^(?:production-test(?:-|$)|logs$|saves$|crash-reports$|\.ssh$|\.aws$|\.azure$)/.test(part))) return false;
  const name = parts.at(-1);
  if (/^(?:\.env(?:\..*)?|credentials(?:\..*)?|secrets?(?:\..*)?|tokens?(?:\..*)?|accounts?(?:\..*)?|cookies?(?:\..*)?|launcher_accounts\.json|auth\.json|id_(?:rsa|ed25519)(?:\.pub)?)$/i.test(name) || /\.(?:pem|p12|pfx|key|keystore|jks|log|lock|sqlite3?|db)$/i.test(name)) return false;
  if (/(?:^|\/)src\//.test(p)) return true;
  if (/^(?:build|verify|preview)\.(?:ps1|sh|cmd|bat)$/.test(p)) return true;
  if (/^(?:libs|lib)\/.+\.(?:jar|zip|class|dll|dylib|so)$/.test(p)) return true;
  if (p.startsWith('tools/')) return /\.(?:py|ps1|mjs|js|java|kt|json)$/.test(p);
  if (p.startsWith('gradle/') || /\/gradle\//.test(p)) return /\.(?:gradle|groovy|properties|toml|json|jar)$/.test(p);
  if (/(?:^|\/)(?:build|settings)\.gradle(?:\.kts)?$/.test(p) || /(?:^|\/)(?:gradle\.properties|gradlew(?:\.bat)?)$/.test(p)) return true;
  if (p === 'config/detekt/detekt.yml') return true;
  if (/^\.github\//.test(p)) return /\.(?:yml|yaml|md|py)$/.test(p);
  if (!p.includes('/') && /^(?:\.gitignore|\.gitattributes|\.pre-commit-config\.yaml|FILEHEADER|LICENSE|crowdin\.yml|[A-Za-z_-]+\.md)$/.test(p)) return true;
  return /^(?:[^/]+\/)?(?:LICENSE|\.gitignore)$/.test(p);
}

export async function collectSource(sourceRoot = DEFAULT_SOURCE) {
  sourceRoot = path.resolve(sourceRoot);
  const before = gitState(sourceRoot);
  const tracked = new Set(git(sourceRoot, 'ls-files', '--cached', '-z').split('\0').filter(Boolean).map(slash));
  const gitVisible = git(sourceRoot, 'ls-files', '--cached', '--others', '--exclude-standard', '-z').split('\0').filter(Boolean).map(slash);
  const disk = await walk(sourceRoot);
  const discovered = disk.filter(p => /(?:^|\/)src\//.test(p) || /(?:^|\/)(?:build|settings)\.gradle(?:\.kts)?$/.test(p) || /^(?:gradle|tools)\//.test(p));
  const candidates = ordered(new Set([...gitVisible, ...discovered])).filter(fingerprintInput);
  const files = [], uiSources = [], missingTracked = [];
  for (const p of candidates) {
    if (!(await exists(localPath(sourceRoot, p)))) { missingTracked.push(p); continue; }
    const bytes = await regularBytes(sourceRoot, p), kind = kindOf(p);
    const text = ['source', 'test', 'build-contract', 'documentation'].includes(kind) || /\.(?:json|lang|fsh|txt|svg)$/.test(p) ? bytes.toString('utf8') : null;
    const record = { path: p, kind, tracked: tracked.has(p), bytes: bytes.length, sha256: hash(bytes) };
    if (text !== null) record.lineCount = text.split('\n').length;
    files.push(record);
    if (kind === 'source') {
      let sites;
      try { sites = scanSurfaces(p, text); } catch (e) { throw new Error(p + ": " + e.message); }
      if (uiScope(p) || sites.length) uiSources.push({ path: p, sha256: record.sha256, domain: domainOf(p), sites });
    }
  }
  const assets = files.flatMap(f => {
    const marker = '/src/main/resources/assets/oneconfig/', at = f.path.indexOf(marker);
    if (at < 0) return [];
    const logical = f.path.slice(at + marker.length);
    const byteParity = /\.(?:svg|png|jpg|jpeg|webp|gif|otf|ttf|woff2?|ogg|wav|mp3)$/.test(logical) || /^lang\//.test(logical) ;
    return [{ source: f.path, logicalPath: logical, bytes: f.bytes, sha256: f.sha256, requirement: byteParity ? 'byte-identical' : logical === 'sounds.json' ? 'namespace-sound-descriptor' : 'render-or-access-contract', destination: 'src/main/resources/assets/muoniumconfigskija/' + logical }];
  });
  const after = gitState(sourceRoot);
  if (json(before) !== json(after)) throw new Error('Upstream git state changed during scan; rerun after edits stop.');
  // A second hash read detects concurrent edits even when a dirty path remains dirty.
  for (const file of files) if (hash(await regularBytes(sourceRoot, file.path)) !== file.sha256) throw new Error('Upstream bytes changed during scan: ' + file.path);
  return {
    schemaVersion: 1, policyReviewedOn: POLICY_DATE,
    source: { root: slash(sourceRoot), ...before, clean: before.statusPorcelainV1Z.length === 0, gitReadPolicy: 'Every Git call uses -c safe.directory=<source>; GIT_OPTIONAL_LOCKS=0; no Git/global configuration writes.' },
    scope: { tracked: 'Allowlisted tracked source/tests/assets/build contracts/wrappers and repository policy inputs only; runtime files and credential containers are excluded before reading.', discovery: 'Git-visible untracked files plus filesystem-discovered src/, gradle/ and tools/ inputs, including ignored source/assets.', excludedGeneratedDirectoryNames: ordered(SKIP_DIRS), exclusions: ['production-test* directories (tracked inputs are still hashed)'], privacy: 'No run/build/dist/log/save data, account/token/cookie/secret containers, private keys, databases, .env files or arbitrary runtime config directories are fingerprinted.', detectorLimit: 'Lexical locations and manually anchored behaviors; not a Kotlin compiler, dynamic call-graph proof, runtime API verification or proof of complete behavior.' },
    sourceTreeSha256: hash(files.map(f => f.path + '\0' + f.sha256).join('\n') + '\nmissing:' + missingTracked.join('\n')),
    missingTracked, files, uiSources, assets,
    statistics: { files: files.length, sourceFiles: files.filter(f => f.kind === 'source').length, resources: files.filter(f => f.kind === 'resource').length, uiSourceFiles: uiSources.length, surfaceSites: uiSources.reduce((n, f) => n + f.sites.length, 0), byteParityAssets: assets.filter(a => a.requirement === 'byte-identical').length },
  };
}

export const BEHAVIOR_CATALOG = [
  [
    "shell-layout",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/Shell.kt",
    "fun Shell(",
    "外壳的固定设计坐标、边距、阴影与多层辉光一致。",
    "同一窗口大小与 UI scale 对比侧栏、页头、内容区边界和滚动裁剪。",
    "改变 page/sidebar/glow opacity，核对各层独立透明度和叠加结果。"
  ],
  [
    "shell-brand-avatar",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Sidebar.kt",
    "private fun Account(",
    "侧栏品牌、头像、玩家名、版本与外链 hover 完整显示。",
    "有/无玩家头像及较长用户名分别截图，检查回退图和文字截断。",
    "验证亮/暗及 Minecraft 主题的品牌和图标变体。"
  ],
  [
    "shell-bell",
    "notifications",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Sidebar.kt",
    "private fun NotificationBell(",
    "通知铃铛未读计数和弹出中心与实际历史同步。",
    "新通知到来时核对徽标，打开中心后未读状态更新。",
    "外点/Esc 关闭后继续收到通知，重开验证历史。"
  ],
  [
    "header-navigation",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Header.kt",
    "fun Header(",
    "页头标题、作者信息、前进/后退和打开原始配置屏行为一致。",
    "从 Mods 进入配置，再前进/后退，核对标题、作者 tooltip 和按钮状态。",
    "有原始配置屏入口时打开并返回，验证焦点和页面保留。"
  ],
  [
    "header-search",
    "search",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Header.kt",
    "fun GlobalSearchBar(",
    "全局搜索栏随当前页面变更提示、查询内容和焦点。",
    "在不同页面输入、清空、回退，核对各页面查询隔离。",
    "启用/关闭 instantSearch，验证 Enter、快捷键、Esc 和中文输入。"
  ],
  [
    "routes-builtins",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/Pages.kt",
    "object BuiltinPages",
    "常用、Mods、Profiles、Themes、Preferences、Credits 的路由、分组和顺序一致。",
    "逐个从侧栏进入，核对选中态、标题与历史栈。",
    "不存在/被替换页面目标的处理要留实际记录。"
  ],
  [
    "routes-extension",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/OneConfigPages.kt",
    "fun register(",
    "外部自定义页面注册、注销、命名空间和配置分类直达契约需保留或明确验收。",
    "注册带搜索与侧栏的页面，打开、注销、重开检查可见结果。",
    "验证自定义配置 modId/category 目标解析及缺失目标。"
  ],
  [
    "shell-page-memory",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/ShellController.kt",
    "class ShellSession",
    "关闭/恢复的页面、搜索、滚动与生命周期保留策略一致。",
    "按 rememberLastPage 各策略重开，核对关闭时间与返回位置。",
    "HUD 编辑器恢复和普通页面恢复分别记录。"
  ],
  [
    "shell-scroll-memory",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/ScrollMemory.kt",
    "fun ",
    "每个页面与分类的滚动锚点独立保存。",
    "滚动多个分类后切换和返回，确认锚点及底部位置。",
    "修改内容高度、过滤结果和窗口大小，验证锚点不越界。"
  ],
  [
    "shell-wheel-containment",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/VerticalScrollContainment.kt",
    "fun ",
    "嵌套滚动、滚动条拖动和到边界的滚轮传递一致。",
    "在下拉、列表和页内容边界滚动，核对消费顺序。",
    "不同分辨率和 GUI scale 下拖滚动条验证坐标。"
  ],
  [
    "shell-open-close",
    "animation",
    "src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt",
    "class OneConfigUIScreen",
    "每棵车道的开/关屏过渡、返回原屏幕、焦点与暂停语义一致。",
    "逐组合对比动画启用/禁用、半途关闭/重开、失焦/resize。",
    "关屏后验证鼠标捕获、游戏暂停和渲染状态恢复。"
  ],
  [
    "shell-page-motion",
    "animation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OneConfigInterface.kt",
    "fun OneConfigInterface(",
    "页面切换、首次页面动画及保留退出内容的过渡一致。",
    "前进/后退和搜索页切换录屏，核对方向、时长、起止状态。",
    "切换 showPageAnimations/showOpeningPageAnimation 和零时长，验证最终状态。"
  ],
  [
    "shell-retained-exit",
    "animation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/RetainedVisibility.kt",
    "fun ",
    "退出动画完成前保留内容，反向/中断时不闪烁或复用过期输入。",
    "快速开关同一弹层和切页，逐帧检查旧内容的释放时机。",
    "在退出过程中点击/按键，核对正确的输入拥有者。"
  ],
  [
    "mods-grid",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt",
    "fun ColumnScope.ModsGrid(",
    "Mods 卡片分组、类别筛选、布局与真实配置集合一致。",
    "不同类别、无配置和大量配置时检查网格、折叠组和滚动。",
    "打开卡片确认真实宿主配置而非演示数据。"
  ],
  [
    "mods-card-actions",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt",
    "fun ModCard(",
    "卡片开关、收藏、隐藏、原屏入口和 hover 行为一致。",
    "切换卡片开关、星标、眼睛图标并重开，核对持久化。",
    "隐藏/禁用/不提供开关的卡片分别检查菜单和可点击区域。"
  ],
  [
    "mods-order-drag",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt",
    "private fun commitDrop(",
    "卡片拖动排序、收藏区落点和自定义顺序保存一致。",
    "跨行、分组和收藏区拖动，核对占位提示及最终顺序。",
    "取消拖动、搜索中拖动和重开页面检查恢复。"
  ],
  [
    "mods-sort",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ModSortControl.kt",
    "fun ModSortControl(",
    "排序分段按钮、当前方向与自定义顺序一致。",
    "切换每种排序模式并核对选择态、结果与记忆。",
    "在手工拖动后切换排序，再返回验证原自定义顺序。"
  ],
  [
    "mods-extension",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModsPageDecorators.kt",
    "object ",
    "卡片装饰器、分组/类型、开关与隐藏扩展有明确兼容验收。",
    "注册自定义装饰和类型、改变排序再注销，核对稳定布局。",
    "异常/空扩展要展示可诊断结果且保留其余页面。"
  ],
  [
    "favorites-host-tree",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Favorites.kt",
    "fun Favorites(",
    "常用页从宿主 FavoritesTree 展示并修改实际设置。",
    "核对宿主常用条目、分类、值和缺树时行为。",
    "修改后检查播放器/HUD 的真实结果及重开持久化。"
  ],
  [
    "config-category-layout",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt",
    "fun ConfigScreen(",
    "配置分类、子分类、accordion、双列/宽控件布局一致。",
    "逐分类展开/收起，检查长标签、宽滑块和嵌套设置。",
    "窗口缩放、动态属性出现/消失时核对滚动与高度。"
  ],
  [
    "config-display-rules",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt",
    "internal fun rememberDisplay(",
    "属性隐藏/禁用/条件显示和装饰器实时更新。",
    "切换依赖属性，验证关联行 alpha、交互与重新显示值。",
    "重置后同一控件立即重新读取默认值。"
  ],
  [
    "config-local-search",
    "search",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt",
    "private fun rememberSearchResults(",
    "配置内搜索保留分组、匹配行和嵌套树上下文。",
    "使用分类名、属性名、描述、无结果查询并清空。",
    "从全局搜索跳入具体属性，核对滚动定位与高亮。"
  ],
  [
    "option-menu-reset",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/Options.kt",
    "fun OptionContextMenu(",
    "设置行操作按钮与右键菜单支持默认值恢复、解绑及禁用态。",
    "对普通属性、改键属性分别右键，核对 Reset/Unbind 的可用性。",
    "恢复默认后核对控件、真实属性与持久化值同时更新。"
  ],
  [
    "option-custom-visualizer",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/OptionWidgets.kt",
    "object ",
    "自定义 visualizer/OptionWidgets 与真实属性读写兼容。",
    "注册自定义控件后打开、改值、重置和卸载，检查数据一致。",
    "未知 visualizer 与错误元数据要保留可诊断行为。"
  ],
  [
    "option-boolean",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/Options.kt",
    "fun BooleanOption(",
    "布尔 switch/checkbox 的状态、hover、禁用与属性监听一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-number",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberOption.kt",
    "fun NumberOption(",
    "数值编辑的范围、格式、步进和非法输入处理一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-slider",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/SliderOption.kt",
    "fun SliderOption(",
    "滑块拖动、数值输入、边界与声音节流一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-inherited-slider",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/InheritableSliderOption.kt",
    "fun InheritableSliderOption(",
    "可继承滑块的继承/覆盖开关与真实值一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-range",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RangeOption.kt",
    "fun RangeOption(",
    "双端范围输入、交叉拖动与边界约束一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-number-chain",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberChainOption.kt",
    "fun NumberChainOption(",
    "多值链式数值输入与联动约束一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-text",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/TextOption.kt",
    "fun TextOption(",
    "文本输入、选择、复制粘贴、提交和失焦行为一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-dropdown",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/DropdownOption.kt",
    "fun DropdownOption(",
    "下拉选择、当前值、滚动与外点关闭一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-multi-select",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/MultiSelectDropdownOption.kt",
    "fun MultiSelectDropdownOption(",
    "多选下拉的勾选、摘要和空选择一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-radio",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RadioButtonOption.kt",
    "fun RadioButtonOption(",
    "单选控件的选中、hover、禁用与属性更新一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-action-info",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ButtonOption.kt",
    "fun ButtonOption(",
    "操作按钮执行真实动作并展示一致的等待/结果。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-file",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/FileOption.kt",
    "fun FileOption(",
    "文件选择、取消、路径显示与真实文件属性持久化一致。",
    "按源控件逐个状态截图并记录实际属性前后值。",
    "覆盖键鼠、禁用、重置、非法/空输入与重开。"
  ],
  [
    "option-list-textlist",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/TextListOption.kt",
    "fun TextListOption(",
    "列表 TextList 的添加/删除/编辑/排序与持久化一致。",
    "空、单项、多项和长项列表检查布局、滚动与错误提示。",
    "操作后重开并恢复默认，核对真实元素顺序和值。"
  ],
  [
    "option-list-numberlist",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberListOption.kt",
    "fun NumberListOption(",
    "列表 NumberList 的添加/删除/编辑/排序与持久化一致。",
    "空、单项、多项和长项列表检查布局、滚动与错误提示。",
    "操作后重开并恢复默认，核对真实元素顺序和值。"
  ],
  [
    "option-list-rangelist",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RangeListOption.kt",
    "fun RangeListOption(",
    "列表 RangeList 的添加/删除/编辑/排序与持久化一致。",
    "空、单项、多项和长项列表检查布局、滚动与错误提示。",
    "操作后重开并恢复默认，核对真实元素顺序和值。"
  ],
  [
    "option-list-colorlist",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorListOption.kt",
    "fun ColorListOption(",
    "列表 ColorList 的添加/删除/编辑/排序与持久化一致。",
    "空、单项、多项和长项列表检查布局、滚动与错误提示。",
    "操作后重开并恢复默认，核对真实元素顺序和值。"
  ],
  [
    "option-list-filelist",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/FileListOption.kt",
    "fun FileListOption(",
    "列表 FileList 的添加/删除/编辑/排序与持久化一致。",
    "空、单项、多项和长项列表检查布局、滚动与错误提示。",
    "操作后重开并恢复默认，核对真实元素顺序和值。"
  ],
  [
    "option-list-draggablelist",
    "settings",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/DraggableListOption.kt",
    "fun DraggableListOption(",
    "列表 DraggableList 的添加/删除/编辑/排序与持久化一致。",
    "空、单项、多项和长项列表检查布局、滚动与错误提示。",
    "操作后重开并恢复默认，核对真实元素顺序和值。"
  ],
  [
    "color-hsva-hex",
    "color-gradient",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt",
    "internal fun ColorPickerPopup(",
    "颜色弹层的 HSV 平面、Hue/Alpha、十六进制输入与预览一致。",
    "拖动每个平面/滑条并输入合法/非法 HEX，核对值与焦点。",
    "核对 alpha 棋盘、弹层边界、外点关闭及重开取值。"
  ],
  [
    "color-gradient",
    "color-gradient",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt",
    "val showGradient",
    "渐变开关、两端颜色、渐变预览与 PolyColor 持久化一致。",
    "分别编辑两端并切换渐变，核对真实 HUD/主题渐变。",
    "确认无渐变能力的类型不提供不可保存的选项。"
  ],
  [
    "color-chroma",
    "color-gradient",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt",
    "internal fun ChromaColorAnimation(",
    "Chroma 开关、速度、实时色相和与渐变互斥的预览一致。",
    "切换 Chroma、速度上下限并逐帧对比；关闭弹层后核对 HUD。",
    "Int/Color 与 PolyColor 属性分别验证能力边界。"
  ],
  [
    "keybind-recording",
    "keybind",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/KeybindOption.kt",
    "fun KeybindOption(",
    "改键录制支持键盘/鼠标/修饰键、提交、取消、解绑和复位。",
    "单键、多键、鼠标、Esc 与空录制分别测试，检查显示名。",
    "录制时不能触发其它全局动作；结束后绑定真实生效。"
  ],
  [
    "keybind-conflicts",
    "keybind",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/KeybindConflicts.kt",
    "private fun computeConflictMap(",
    "改键冲突检测与提示实时反映配置值。",
    "制造同组合键冲突、解绑一侧并重置，检查双方提示更新。",
    "配置树新增/删除与 profile 切换后重新核对冲突。"
  ],
  [
    "keybind-provider",
    "keybind",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/KeybindCatalog.kt",
    "object KeybindProviderRegistry",
    "外部键位 provider、组折叠、Minecraft 绑定与配置绑定完整汇总。",
    "注册两个同 modId 的来源，检查分组去重和标签。",
    "注销、改键与配置刷新后检查实际游戏设置与显示。"
  ],
  [
    "keybind-focus-routing",
    "keybind",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/KeybindManager.kt",
    "fun register(",
    "全局键位注册/替换/焦点拦截与按下释放时序一致。",
    "在文本框、弹层、HUD 编辑器和正常游戏分别按同一组合。",
    "切屏/失焦/重开后确认无卡住的按下态。"
  ],
  [
    "profiles-grid-create",
    "profiles",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt",
    "private fun CreateProfileCard(",
    "配置档分组、创建、搜索、图标和名称校验一致。",
    "创建空/重名/长名档，核对校验提示和新增卡片。",
    "在各类别搜索与清空，检查选中档与滚动。"
  ],
  [
    "profiles-actions",
    "profiles",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt",
    "private fun ProfileActionsMenu(",
    "配置档重命名、克隆、换图标、删除和激活菜单一致。",
    "逐动作测试确认/取消、当前档限制和默认克隆名称。",
    "激活后验证播放器/HUD/键位的真实配置值与提示。"
  ],
  [
    "profiles-file-operations",
    "profiles",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt",
    "fun <T : Any> runProfileDialog(",
    "配置档导入/导出选择器、取消、失败与文件格式兼容。",
    "导入有效、损坏与旧档；导出后再导入核对数据。",
    "文件选择取消和异常都恢复交互，不丢当前档。"
  ],
  [
    "profiles-busy-overlay",
    "profiles",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt",
    "private fun ProfileBusyOverlay(",
    "异步配置档操作显示忙碌遮罩并防止重复动作。",
    "慢操作时核对遮罩先显示、按钮/输入被拦截。",
    "操作失败/关屏后回调不修改已过期页面。"
  ],
  [
    "preset-catalog",
    "presets",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt",
    "fun loadCatalog(",
    "预设模板目录、分类/搜索、详情与来源标签一致。",
    "列出内置与用户卡片，搜索并进入详情。",
    "目录空/错误/刷新时核对提示和选择保留。"
  ],
  [
    "preset-preview-facets",
    "presets",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt",
    "fun applySelection(",
    "预设 facet 选择、差异预览、部分应用和撤销真实生效。",
    "选择外观/音频组合，核对预览和实际改动范围。",
    "应用后撤销，检查未选项未被修改且旧状态恢复。"
  ],
  [
    "preset-save-share",
    "presets",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt",
    "fun saveCurrent(",
    "预设保存、分享码复制与删除确认完整实现。",
    "输入名称/选择 facet 后保存并复制，再读回核对协议内容。",
    "删除确认/取消及失败要显示真实结果。"
  ],
  [
    "preset-import",
    "presets",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt",
    "fun importTextContent(",
    "预设分享码/文本/文件导入和打开预设目录行为一致。",
    "粘贴合法/非法/旧版分享码；从文件导入并处理取消。",
    "导入后预览、应用、重开目录确认真实保存。"
  ],
  [
    "themes-four-variants",
    "theme",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Impls.kt",
    "val PolyGlassDark",
    "PolyGlass/Minecraft 各深浅主题的色板、图标、圆角与预览一致。",
    "四个主题分别对照所有页面、菜单、控件与通知。",
    "核对 Minecraft 图标覆盖、像素边缘和明暗文字对比。"
  ],
  [
    "themes-selection-motion",
    "theme",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Themes.kt",
    "private fun ThemeCard(",
    "主题卡片 hover、选中、预览与即时切换/持久化一致。",
    "逐卡片选择并重开，检查预览与当前主题标识。",
    "动画中切主题，验证颜色/字体/图标不会混用旧缓存。"
  ],
  [
    "themes-pixel-grid",
    "theme",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Provider.kt",
    "internal fun snapScaleToPixelGrid(",
    "像素主题缩放依据屏幕比和网格模式对齐。",
    "多种窗口大小、UI scale 和 pixelGrid 模式对比像素边界。",
    "检查文本/图标/点击位置与视觉布局一致。"
  ],
  [
    "theme-accent-gradient",
    "theme",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Provider.kt",
    "fun accentHorizontalBrush(",
    "主题强调色与渐变传递到品牌、控件、辉光和通知。",
    "切换强调色/渐变，核对弹层与已显示通知的更新。",
    "重开后核对颜色持久化与各层 alpha。"
  ],
  [
    "font-source-hot-swap",
    "font-language",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/UiFontOverride.kt",
    "fun set(",
    "宿主替换字体通过 revision 失效缓存，字重与 CJK 回退完整。",
    "热切字体/恢复默认/同名文件内容变化后比较页面与 HUD。",
    "检查 regular/medium/semibold/bold、缺失字形和旧资源释放。"
  ],
  [
    "font-files-fallback",
    "font-language",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/font/UiFontFiles.kt",
    "fun set(",
    "外部字体目录、常规/粗体及回退读取契约完整。",
    "有/无目录、缺粗体、损坏字体和缺回退字体分别打开。",
    "核对日志/可见降级、字重回退及重开行为。"
  ],
  [
    "language-refresh",
    "font-language",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/I18n.kt",
    "fun localizedString",
    "语言与格式化文案随宿主语言变化更新。",
    "中英文切换后核对页头、HUD tour、通知、菜单和搜索。",
    "带参数、缺翻译与长文本分别检查回退和排版。"
  ],
  [
    "preferences-schema",
    "settings",
    "muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/internal/OneConfigConfig.java",
    "public static boolean keybindClosesGui",
    "偏好页的完整配置 schema/default/key 与持久化兼容。",
    "逐项核对键位、模糊、透明度、暂停、缩放、记忆、动画、搜索与声音开关。",
    "迁移已有 oneconfig.json，改值重开确认实际效果和持久化。"
  ],
  [
    "credits-links",
    "shell-navigation",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Credits.kt",
    "fun Credits(",
    "致谢分组、翻译者、链接、头像与语言过滤一致。",
    "中英文分别对照作者/翻译者名单和长名排版。",
    "验证每个外链的目标及点击区域。"
  ],
  [
    "search-global-results",
    "search",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/SearchResultsScreen.kt",
    "fun SearchResultsScreen(",
    "全局搜索按配置/页面分组，显示匹配上下文并准确跳转。",
    "查询标题、描述、类别和未知词，核对结果排序与空态。",
    "点击结果检查目标页/分类/设置高亮与后退状态。"
  ],
  [
    "search-provider-registry",
    "search",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchProviderRegistry.kt",
    "fun registerSearchProvider(",
    "搜索 provider 按优先级与可用性选择并建立语料。",
    "注册高优先级、不可用和抛异常 provider，核对回退。",
    "注销后搜索必须切回当前可用 provider。"
  ],
  [
    "search-matching-distance",
    "search",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/DefaultSearchProvider.kt",
    "internal fun searchMatches(",
    "默认搜索的大小写/子串/容错距离与设置一致。",
    "分别设置 searchDistance，用精确词、错字、短词和中文查询对照。",
    "局部搜索和全局搜索使用同一原始案例核对可见结果。"
  ],
  [
    "search-public-api",
    "search",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchProviders.kt",
    "object SearchProviders",
    "公开 SearchProviders、SearchScope 和 PageSearchQuery 扩展契约完整。",
    "通过公开 API 注册 provider/文档，检查不同 scope 的结果。",
    "注销、动态配置变更与页面关闭后的语料更新要留记录。"
  ],
  [
    "notifications-history",
    "notifications",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/NotificationsCenter.kt",
    "fun NotificationsCenter(",
    "通知中心显示类型图标/颜色、相对时间、动作、移除和清空历史。",
    "推送各 NotificationType 与长文本，核对顺序、时间和滚动。",
    "点击动作/删除/清空，确认历史、未读和活动 toast 正确变化。"
  ],
  [
    "notifications-toasts",
    "notifications",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationToasts.kt",
    "fun NotificationToasts(",
    "游戏全局 toast 的堆叠、进出动画、文字换行和点击区域完整。",
    "同时推多条、长文和带动作通知，逐帧核对位置与遮挡。",
    "在主界面/HUD 编辑器/普通游戏触发并验证输入命中。"
  ],
  [
    "notifications-progress",
    "notifications",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationToasts.kt",
    "fun sampleProgress(",
    "toast 的寿命进度、外部进度和 hover 暂停语义一致。",
    "自动超时、持续通知、动态进度和悬浮暂停分别计时录屏。",
    "移开鼠标/关屏后验证进度和退出状态。"
  ],
  [
    "notifications-input",
    "notifications",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/ToastInput.kt",
    "fun install(",
    "toast 悬浮/点击/动作与游戏输入路由一致。",
    "点击主体、动作、外部与多层交叠处，核对仅正确目标触发。",
    "失焦/隐藏/移除通知后不得保留旧 hover 或吞输入。"
  ],
  [
    "notifications-thread-history",
    "notifications",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationsManager.kt",
    "fun push(",
    "跨线程推送、活动/历史/已读状态与通知动作生命周期一致。",
    "后台线程连续推送/更新/移除，观察顺序和显示。",
    "清历史与关闭活动通知分别验证，不能将两者混同。"
  ],
  [
    "hud-studio-entry",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "fun HudDesignStudio(",
    "HUD 选择、设置/设计分页、可拖面板和退出/恢复一致。",
    "无 HUD、有选中、多选及原 HUD 已删除时分别打开。",
    "拖面板、切分类和重开，检查位置、选择与搜索。"
  ],
  [
    "hud-selection-marquee",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "fun updateMarqueeSelection(",
    "点击穿透、重叠 HUD 轮选、多选和框选动画一致。",
    "单击、组合键加选、反向框选及交叠 HUD 逐项对照。",
    "框选过程中 Esc/切屏，确认临时状态和选择正确恢复。"
  ],
  [
    "hud-drag-snap",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun snapAxis(",
    "HUD 拖动、吸附线、对齐和拖动时 UI 透明度一致。",
    "拖到屏幕边/中心和邻居附近，核对阈值、坐标与导线。",
    "锁定 HUD、多选拖动、不同 GUI scale 与窗口 resize 分别测。"
  ],
  [
    "hud-resize",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun resizeHud(",
    "四角缩放、文本缩放/静态尺寸和尺寸标记一致。",
    "分别拖四角，核对最小尺寸、保持角与字体/背景大小。",
    "缩放时切 GUI scale 或取消操作，检查坐标不跳变。"
  ],
  [
    "hud-action-context",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun HudActionBar(",
    "HUD 浮动操作条、右键菜单、锁定提示与命中顺序一致。",
    "对重叠、锁定和多选 HUD 检查所有动作与 tooltip。",
    "菜单临近窗口边缘时核对定位和关闭。"
  ],
  [
    "hud-copy-group",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "internal fun duplicateHudGroup(",
    "HUD 复制/剪切/粘贴/克隆保留配置并重建组内关联。",
    "复制一个 HUD 与锚定/合并组合，再粘贴核对独立属性。",
    "非可克隆 HUD、取消和删除后撤销分别记录。"
  ],
  [
    "hud-undo-redo",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudHistory.kt",
    "internal class HudHistory",
    "HUD 布局历史合并连续操作，撤销重做还原实体和属性。",
    "拖动/缩放/改样式/新增/删除后连续撤销重做。",
    "跨 profile、切屏和异步修改后核对历史拥有者。"
  ],
  [
    "hud-shortcut-session",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignSession.kt",
    "object HudDesignSession",
    "HUD 快捷键统一进入当前设计会话并尊重文字焦点。",
    "复制/剪切/粘贴、删除、全选、锁定、隐藏、设置、撤销和重做逐个实测。",
    "弹层录制键位/文本输入/已退出编辑器时不误执行。"
  ],
  [
    "hud-library-sections",
    "hud-library",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun HudLibraryPanel(",
    "HUD 库按 mod 分组、搜索、图标导航和滚动定位一致。",
    "多来源/空来源/Compat HUD 混合时对照分组与标签。",
    "点击 mod 图标、搜索和滚动到底，检查当前分组高亮。"
  ],
  [
    "hud-library-layout",
    "hud-library",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun packHudLibraryRows(",
    "HUD 库依据自然预览尺寸排版，卡片/间距/滚动条一致。",
    "用宽窄/高矮 HUD 混排，调整窗口和 UI scale 对比换行。",
    "预览状态变化后核对缓存失效与点击区域。"
  ],
  [
    "hud-library-add",
    "hud-library",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun placeHudCentered(",
    "点击和拖出 HUD 库卡片都创建真实 HUD，并保持正确初始位置。",
    "点击添加、拖出到游戏区、在外壳内释放取消逐项实测。",
    "空选中自动展开库、添加后设置面板与选择要正确更新。"
  ],
  [
    "hud-library-preview",
    "hud-library",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "private fun ComposeHudPreviewCard(",
    "原生/Compat HUD 预览、hover 添加覆盖与比例一致。",
    "对比真实 HUD 和卡片预览的文字、背景、阴影与裁剪。",
    "检查旧 HUD 渲染路径、缺预览与动态内容。"
  ],
  [
    "hud-tour-nine-steps",
    "hud-tour",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudTour.kt",
    "internal enum class HudTourStep",
    "HUD tour 的 Welcome/Adding/Dragging/Merging/Anchoring/Settings/Designer/Fonts/Done 九步完整。",
    "首次进入、手动重播、前后切步、关闭和完成后重开逐项验证。",
    "检查每步聚焦目标、高亮遮罩、说明卡避让和实际页面类别。"
  ],
  [
    "hud-tour-interactive-merge",
    "hud-tour",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "fun moveTour(",
    "导览合并步骤依据真实拖动合并事件推进，而非纯静态演示。",
    "无可合并 HUD、锁定 HUD 和真实合并成功分别观察提示和推进条件。",
    "退出导览要恢复选择/库/面板临时状态并保存已看标志。"
  ],
  [
    "hud-anchor-target",
    "hud-anchor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/Hud.kt",
    "fun anchorTo(",
    "HUD 自身/目标九点锚定、清除和环路约束一致。",
    "将多个 HUD 互相锚定，改变目标尺寸/位置后观察跟随。",
    "清锚、删除目标与尝试环路，核对位置和数据。"
  ],
  [
    "hud-anchor-growth-pin",
    "hud-anchor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/Hud.kt",
    "fun setGrowthAnchorKeepingPosition(",
    "增长方向、自身锚点和固定角更改保持屏幕位置语义。",
    "改变内容宽高、增长点、自锚点和固定角，逐步记录坐标。",
    "resize/GUI scale/profile 切换后检查锚点仍有效。"
  ],
  [
    "hud-background-merge",
    "hud-anchor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/HudBackgroundMerge.kt",
    "object HudBackgroundMerge",
    "相邻/对角 HUD 背景合并、圆角和隐藏保留背景规则一致。",
    "横排、竖排、对角、不同色/圆角/边距组合逐个截图。",
    "开启/关闭 merge diagonally 和 keep background when hidden 后核对组背景。"
  ],
  [
    "hud-merge-drag-group",
    "hud-anchor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt",
    "dragTookWholeMerge",
    "合并组整体拖动、单体分离与重新接合的规则一致。",
    "普通拖动与分离修饰键分别对比组成员坐标。",
    "有锁定成员、锚定成员和多选时验证排除集合与结束清理。"
  ],
  [
    "hud-settings-background",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Settings.kt",
    "fun Settings(",
    "HUD 可见性、锁定、GUI scale、背景/阴影与显示场景设置一致。",
    "逐项更改背景色/Chroma/圆角/阴影及 F3/Tab/GUI/Chat 可见性。",
    "恢复位置/删除 HUD 后核对真实实例、选择与撤销。"
  ],
  [
    "hud-designer-size-padding",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Designer.kt",
    "Section(\"Size & Alignment\"",
    "设计器尺寸、对齐、静态大小、边缘和间距布局设置完整。",
    "动态与静态尺寸模式分别编辑宽高、边距、三种间距分配。",
    "检查多选、无选择与搜索过滤后的控件状态。"
  ],
  [
    "hud-designer-text",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Designer.kt",
    "Section(\"Text Options\"",
    "HUD 文字字体、字号、粗体/斜体、字重、对齐与大小写样式一致。",
    "Poppins/Minecraft 两条字体路径、三种对齐/大小写和修饰组合截图。",
    "预览与真实 HUD 逐字对照，核对文字尺寸与阴影。"
  ],
  [
    "hud-text-render-format",
    "hud-editor",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/TextHud.kt",
    "override fun Content(",
    "文本 HUD 的前后缀、大小写、换行、字形格式与时间更新一致。",
    "空文本、长行、多行和格式码分别对比。",
    "日期时间 HUD 的格式错误、更新频率和克隆后状态要实测。"
  ],
  [
    "sound-nine-events",
    "sound",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSoundEvent.kt",
    "enum class UiSoundEvent",
    "OPEN/CLOSE/CLICK/SLIDER_TICK/HUD_SELECT/DRAG_START/DRAG_END/RESIZE_START/RESIZE_END 九类音效触发一致。",
    "逐事件记录触发次数、顺序和音量，拖动长过程不能重复触发起始音。",
    "总开关和各类子开关都要改变实际声音。"
  ],
  [
    "sound-theme-routing",
    "sound",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSounds.kt",
    "fun play(",
    "UI 音效服务路由、主题、音量边界和无服务降级一致。",
    "两种音色主题、静音/恢复与零音量分别听测。",
    "缺资源/缺服务时界面仍可用且不伪报有声音。"
  ],
  [
    "sound-ambience-life",
    "sound",
    "muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSounds.kt",
    "fun acquireAmbience(",
    "界面环境循环的获取/释放、主题切换与音量即时更新一致。",
    "开关屏、编辑器切换、重入与主题切换时核对不叠加循环。",
    "退出/失焦/游戏声音引擎重载后核对停止与恢复。"
  ],
  [
    "sound-ducking-host",
    "sound",
    "src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt",
    "class McUiSoundService",
    "各车道实际音效映射、环境声和游戏音乐 ducking/恢复完整。",
    "背景音乐播放时打开/关闭设置中心，核对开启/禁用 ducking。",
    "引擎重载、缺文件与快速重开后不能留下低音量或未释放声源。"
  ],
  [
    "host-background-blur-1.21.11",
    "blur",
    "1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt",
    "object UiBlur",
    "1.21.11 的现代渲染提取/后处理模糊路径需要独立适配和验收。",
    "核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。",
    "逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。"
  ],
  [
    "host-background-blur-26.1.2",
    "blur",
    "26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt",
    "object UiBlur",
    "26.1.2 的现代渲染提取/后处理模糊路径需要独立适配和验收。",
    "核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。",
    "逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。"
  ],
  [
    "host-background-blur-26.2",
    "blur",
    "26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt",
    "object UiBlur",
    "26.2 的现代渲染提取/后处理模糊路径需要独立适配和验收。",
    "核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。",
    "逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。"
  ],
  [
    "host-background-blur-26.3",
    "blur",
    "26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt",
    "object UiBlur",
    "26.3 的现代渲染提取/后处理模糊路径需要独立适配和验收。",
    "核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。",
    "逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。"
  ]
];

function targetsFor(p) {
  const lane = p.split('/')[0];
  if (!LANES.includes(lane)) return TARGETS;
  const segment = p.split('/')[1];
  return TARGETS.filter(t => t.startsWith(lane + '/') && (!['fabric', 'forge', 'neoforge'].includes(segment) || t.endsWith('/' + segment)));
}
const SITE_EXPECTATIONS = {
  composable: '逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。',
  route: '从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。',
  menu: '核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。',
  'dialog-or-popup': '核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。',
  keybind: '实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。',
  animation: '对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。',
  sound: '对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。',
  blur: '对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。',
  'font-language': '热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。',
  'search-provider': '注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。',
  'peripheral-ui': '从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。',
  'sound-event': '单独触发该声音事件，检查主题音色、音量、启用开关和触发次数。',
  'tour-step': '核对该导览步骤的遮罩、目标区域、文案、可交互推进条件、上一步/下一步与退出恢复。',
};
function featureTemplate(id, domain, title, behavior, acceptance, anchors, sites, targets, review) {
  return {
    id, domain, title, behavior, acceptance, sourceAnchors: anchors, surfaceIds: sites.map(s => s.id), targets,
    sourceFingerprint: hash(json({ anchors, sites: sites.map(s => s.id), behavior, acceptance })),
    semanticReview: { result: review, by: '', notes: '' },
    status: 'pending', implementation: [], implementationReview: { result: 'pending', by: '', notes: '' }, evidence: { automated: [], automatedNeutral: [], game: [], visual: [] },
  };
}
export async function makeFeatureMap(manifest, projectRoot, previous = null) {
  const features = [], issues = [], fileByPath = new Map(manifest.files.map(f => [f.path, f]));
  for (const row of BEHAVIOR_CATALOG) {
    const [id, domain, suffix, needle, behavior, ...acceptance] = row;
    const matches = manifest.files.filter(f => f.path === suffix || f.path.endsWith('/' + suffix));
    const anchors = [];
    for (const f of matches) {
      const lines = (await regularBytes(manifest.source.root, f.path)).toString('utf8').split(/\r?\n/);
      const at = lines.findIndex(l => l.includes(needle));
      if (at < 0) { issues.push(id + ': anchor missing in ' + f.path + ': ' + needle); continue; }
      anchors.push({ path: f.path, line: at + 1, sha256: f.sha256, anchor: needle });
    }
    if (!matches.length) issues.push(id + ': source path missing: ' + suffix);
    const sites = manifest.uiSources.filter(f => anchors.some(a => a.path === f.path)).flatMap(f => f.sites);
    features.push(featureTemplate(id, domain, behavior, behavior, acceptance, anchors, [], ordered(new Set(anchors.flatMap(a => targetsFor(a.path)))), 'anchored-behavior-needs-acceptance'));
  }
  for (const file of manifest.uiSources) {
    const f = fileByPath.get(file.path), kinds = ordered(new Set(file.sites.map(s => s.kind)));
    const acceptance = kinds.map(kind => (SITE_EXPECTATIONS[kind] || '逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。') + ' 定位类型: ' + kind);
    if (!acceptance.length) acceptance.push('该文件支撑 ' + file.domain + '；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。');
    features.push(featureTemplate('file-' + hash(file.path).slice(0, 20), file.domain, file.path, '复核 ' + file.path + ' 的全部 ' + file.sites.length + ' 个定位点及其调用者。', acceptance,
      [{ path: file.path, line: file.sites[0]?.line || 1, sha256: f.sha256, anchor: 'whole-source-file' }], file.sites, targetsFor(file.path), 'required'));
  }
  const oldById = new Map((previous?.features || []).map(f => [f.id, f]));
  for (const feature of features) {
    const old = oldById.get(feature.id);
    if (!old) continue;
    if (old.sourceFingerprint === feature.sourceFingerprint && previous.sourceTreeSha256 === manifest.sourceTreeSha256) {
      for (const key of ['status', 'implementation', 'implementationReview', 'evidence', 'semanticReview']) if (old[key] !== undefined) feature[key] = old[key];
    } else if (old.status !== 'pending' || old.implementation?.length || Object.values(old.evidence || {}).some(e => e.length)) {
      feature.invalidatedEvidence = { reason: 'Upstream baseline or feature contract changed; old evidence retained, current status reset to pending.', previousSourceTreeSha256: previous.sourceTreeSha256, status: old.status, implementation: old.implementation, evidence: old.evidence };
    }
  }
  const ids = new Set(features.map(f => f.id));
  const retiredFeatures = [...(previous?.retiredFeatures || []), ...(previous?.features || []).filter(f => !ids.has(f.id))];
  const previousAssets = new Map((previous?.assets || []).map(a => [a.source, a]));
  const assets = manifest.assets.filter(a => ['byte-identical', 'namespace-sound-descriptor'].includes(a.requirement)).map(a => {
    const old = previousAssets.get(a.source);
    return { ...a, destination: old?.sha256 === a.sha256 ? old.destination : a.destination };
  });
  return {
    schemaVersion: 1, sourceHead: manifest.source.head, sourceTreeSha256: manifest.sourceTreeSha256,
    completionPolicy: 'Only status=pending or complete. complete requires reviewed behavioral cases, exact implementation paths+SHA256, passing behavioral test reports, and per-target game+visual evidence tied to this source tree and implementation snapshot. File/class existence and compilation never imply parity. The checker validates evidence structure and hashes; a reviewer remains responsible for the truth of observations.',
    targets: TARGETS, catalogIssues: issues, retiredFeatures, assets, features,
    evidenceExample: {
      implementation: [{ path: 'src/main/java/<actual implementation>.java', line: 1, sha256: '<actual file hash>' }],
      semanticReview: { result: 'reviewed', by: '<reviewer>', notes: '<observed behavior and dynamic entry points>', cases: [{ id: '<case>', surfaceIds: ['<site-id>'], expected: '<visible result>' }] },
      automatedReport: { schemaVersion: 1, kind: 'behavior', result: 'pass', featureId: '<feature-id>', sourceTreeSha256: '<baseline>', implementationSnapshotSha256: '<snapshot>', targets: ['26.3/fabric'], cases: [{ id: '<reviewed-case>', result: 'pass', assertions: [{ expected: '<expected visible state>', observed: '<actual visible state>', pass: true }] }] },
      record: { featureId: '<feature-id>', caseIds: ['<case>'], targets: ['26.3/fabric'], result: 'pass', kind: 'behavior', sourceTreeSha256: '<baseline>', implementationSnapshotSha256: '<checker summary>', command: '<actual behavioral command or game procedure>', observed: '<actual observations>', report: { path: 'build/acceptance/<report>.json', sha256: '<hash>' }, captures: [{ source: { path: 'build/acceptance/<original>.png', sha256: '<hash>' }, port: { path: 'build/acceptance/<port>.png', sha256: '<hash>' }, comparison: '<geometry, color, typography and animation comparison>' }] },
    },
  };
}
const md = text => String(text).replaceAll('|', '\\|').replaceAll('\n', ' ');
const codeSpan = text => String.fromCharCode(96) + text + String.fromCharCode(96);
export function renderInventory(manifest, map, report) {
  const out = ['# 源码基线与 1:1 验收清单', '', '扫描策略核对：' + POLICY_DATE + '。生成入口：tools/audit-source.mjs。原仓全程只读；默认扫描和 check-parity 不写文件。', '',
    '原仓 HEAD：' + codeSpan(manifest.source.head) + '；源码树 SHA256：' + codeSpan(manifest.sourceTreeSha256) + '。当前原仓工作区：' + (manifest.source.clean ? '干净' : '存在修改，见 source-manifest.json source.statusPorcelainV1Z') + '。', '',
    '基线包含 ' + manifest.statistics.files + ' 个仓库输入、' + manifest.statistics.sourceFiles + ' 个生产源码文件、' + manifest.statistics.resources + ' 个资源文件；定位 ' + manifest.statistics.uiSourceFiles + ' 个 UI 相关源文件中的 ' + manifest.statistics.surfaceSites + ' 个源码位置。字节验收资产 ' + map.assets.filter(a => a.requirement === 'byte-identical').length + ' 项，声音声明仅允许已核实的 oneconfig: → muoniumconfigskija: 命名空间替换。原始声明 SHA256 仍保留。数字仅是本次快照，不是完整行为已还原的证明。', '',
    '## 重跑命令', '', '在 muonium-config-skija 目录执行（Node.js 18+ 内置模块，无需 npm/Gradle）：', '', '~~~powershell',
    'node tools/audit-source.mjs', 'node tools/audit-source.mjs --json', 'node tools/audit-source.mjs --write',
    'node tools/check-parity.mjs', 'node tools/check-parity.mjs --json', 'node tools/check-parity.mjs --stage implementation', 'node tools/audit-source.mjs --self-test', 'node tools/check-parity.mjs --self-test',
    '# 换机器时显式指定原仓；--project 可指定验收工程', 'node tools/check-parity.mjs --source "E:/oneconfig lite/MuoniumConfig-src"', '~~~', '',
    '--write 仅更新 reference/source-manifest.json、reference/feature-map.json、docs/feature-inventory.md；保留同一基线的既有证据。上游漂移会把旧证据保留下来并重置为 pending；被删项目进入 retiredFeatures 等待处理。退出码：0=全部检查满足，1=存在差距，2=工具/输入错误。audit-source 普通扫描成功为 0，表示扫描完成。', '',
    '## 判定边界', '',
    '游戏、视觉、交互和动画的最终验收由人工负责。pending 表示证据待齐，不是失败功能计数。--stage implementation 只针对源码/资产/打包约束和已声明实现的自动行为证据判断可测试状态，完整报告仍保留人工待验收及旧成品待重建项，源侧缺资源与禁用依赖仍阻塞；它不把任何项提升为 1:1 完成，也不是生成测试包的前置门禁。', '',
    'feature-map 中全部项目初始为 pending。类存在、静态核对或编译通过都不能标为 complete。实现必须列真实路径/行号/SHA256；语义案例覆盖本项全部定位点；自动化行为报告、逐组合游戏记录和原版/新实现的视觉证据必须存在且哈希匹配。报告需绑定 featureId、sourceTreeSha256、当前 implementationSnapshotSha256 和准确目标。工具验证证据的完整性与关联，不替人工确认报告事实。', '',
    '普通 JAR 扫描验证已有成品的字节内容，不证明其来自当前源码、Mixin 命中或游戏行为。缺少任一组合的新鲜成品仍是缺口。主仓自带 Skija/Kotlin 的 classpath 不等于本工程打包了它们；禁止的是本工程引入 Compose/Skiko、自带第二套 Skija/native 或打包 Kotlin/loader 类。loader 引用只允许实际 src/acceptance 外壳，host 可接 Minecraft，ui/model 不可接 Minecraft/loader。', '',
    '扫描器定位词法声明/调用、函数类型注解和手工锚定的语义行为；不运行 Kotlin 编译器、反射或动态路由。文件级项目默认需要人工语义复核，全部定位点留在下方；注册扩展、数据驱动页和运行期资源仍须从真实入口实测。', '',
    '## 当前全量差距', '',
    report ? '检查对应的项目输入 SHA256：' + codeSpan(report.implementationSnapshotSha256) + '。检查结果：' + (report.ok ? '全部约束满足' : '仍有缺口') + '；已核实实现/中立自动证据=' + report.summary.implementationChecked + '；1:1 complete=' + report.summary.complete + '/' + report.summary.features + '，pending=' + report.summary.pending + '；资产一致=' + report.summary.assetsMatched + '/' + report.summary.assets + '；检查发现=' + report.findings.length + ' 项。' : '尚未运行 check-parity；不能宣称验收通过。', ''];
  if (report) {
    out.push('| 检查类别 | 未满足数 |', '| --- | ---: |');
    const counts = report.findings.reduce((m, f) => (m[f.code] = (m[f.code] || 0) + 1, m), {});
    for (const key of ordered(Object.keys(counts))) out.push('| ' + key + ' | ' + counts[key] + ' |');
    out.push('', '完整逐项内容由 check-parity --json 输出；下表保留每个语义功能/源文件项目，未覆盖内容不会因限制终端显示而丢弃。', '', '### 字节资产差距', '');
    for (const f of report.findings.filter(f => f.code.startsWith('asset-'))) out.push('- ' + codeSpan(f.path || f.featureId || '') + '：' + md(f.message));
    out.push('', '### 架构、证据与成品检查差距', '');
    for (const f of report.findings.filter(f => !f.code.startsWith('asset-') && !['feature-pending', 'semantic-review-required'].includes(f.code))) out.push('- **' + f.code + '** ' + md(f.path || f.featureId || '') + '：' + md(f.message));
    out.push('');
  }
  out.push('## 需要主助手明确分配的跨域工作', '',
    '- 通知既有侧栏中心，也有游戏全局 toast：悬浮暂停、进度、按钮、已读/历史、关闭命中和渲染层级需要独立验收。',
    '- HUD tour 共九步，含真正完成合并后才推进的交互步骤；HUD 库含分组、预览自动排版、搜索、滚动定位、点击/拖出添加。',
    '- 位置锚定、增长方向、固定角、合并背景与整组合并拖动属于 HUD 运行模型契约；只做编辑器面板不足以验收。',
    '- 字体运行时替换、字重/CJK 回退、语言刷新、玩家头像/品牌 tint、像素主题缩放和图标样式均可能跨页面与宿主职责。',
    '- 搜索 provider 与自定义页面/配置行/控件扩展是公开接入契约，需明确深度绑定后的保留范围，不能静默丢弃。',
    '- 九种 UI 事件声音、主题相关音色、环境循环、音乐 ducking 和 SoundEngine 关闭恢复涉及宿主生命周期。',
    '- 七树背景模糊、开/关屏中断、焦点、暂停、原始屏幕返回、GUI scale 与窗口 resize 要分别实测 14 组合。',
    '- 剪贴板/文件选择器取消、预设协议兼容、profile 切换后的绑定与撤销恢复、异步完成后的过期回调需要异常路径验收。', '',
    '## 行为证据与构建证据的填写', '',
    '自动化报告需 JSON schemaVersion=1、kind=behavior、result=pass，包含 featureId、sourceTreeSha256、implementationSnapshotSha256、targets，以及 cases[{id,result,assertions:[{expected,observed,pass:true}]}]。每份记录的 caseIds 对应 semanticReview.cases。编译日志缺少实际状态断言，不能充当行为报告。游戏与视觉记录每条仅针对一个组合；视觉要成对原版/新实现截图或视频，并描述布局、颜色、字形及动画比较。具体字段模板见 feature-map.evidenceExample。', '',
    '已有成品的新鲜度记录独立写 build/acceptance/artifact-provenance.json：schemaVersion=1、sourceTreeSha256、implementationSnapshotSha256、artifacts[{path,sha256,target,result:"pass",command,log:{path,sha256}}]。路径须指向本工程真实成品及实际构建日志；记录与当前源码哈希不一致仍失败。这不是代替行为/游戏/视觉测试的入口。', '',
    '## 按行为组织的验收项', '', '| ID / 状态 | 源码位置 | 可验收行为与步骤 |', '| --- | --- | --- |');
  for (const f of map.features.filter(f => !f.id.startsWith('file-'))) {
    out.push('| ' + f.id + ' / **' + f.status + '** | ' + f.sourceAnchors.map(a => md(a.path + ':' + a.line)).join('<br>') + ' | ' + md(f.behavior) + '<br>' + f.acceptance.map((s, i) => (i + 1) + '. ' + md(s)).join('<br>') + ' |');
  }
  out.push('', '## 逐源文件定位与待验收面', '', '以下每个文件是独立 pending/complete 单元；与上面的语义项目同时验收，避免只完成主页面而遗漏回调、扩展或宿主。行号对应上述基线；每条位置附源码摘录，注释和字符串不会伪造调用。', '');
  for (const f of map.features.filter(f => f.id.startsWith('file-'))) {
    const p = f.sourceAnchors[0].path, ui = manifest.uiSources.find(s => s.path === p);
    out.push('### ' + p, '', codeSpan(f.id) + '；状态 **' + f.status + '**；领域 ' + f.domain + '；组合 ' + f.targets.join(', ') + '。', '', f.acceptance.join(' '), '');
    if (ui.sites.length) {
      out.push('| 行号 | 类型 / 名称 | 源码摘录 |', '| ---: | --- | --- |');
      for (const site of ui.sites) out.push('| ' + site.line + ' | ' + md(site.kind + ' / ' + site.label) + ' | ' + md(site.excerpt) + ' |');
    } else out.push('无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。');
    out.push('');
  }
  out.push('## 验收顺序建议', '',
    '1. 固定源 HEAD/源树哈希，核对扫描无漂移；先恢复全部字体、图标、图片、语言和声音字节资产。',
    '2. 在同一窗口/GUI scale/主题/语言/字体/设置快照下逐页截图；菜单、弹层、空/错/忙/禁用/hover/焦点均留对照。',
    '3. 录制动画全程，对齐曲线、时长、半途关闭/反向、拖动透明度、页面方向和禁用开关。',
    '4. 逐项测 HUD tour/库/锚定/合并/文字设计、通知、搜索 provider、改键、profile 与预设异常路径。',
    '5. 将实际行为报告、截图与实现哈希填入 feature-map；按 14 个准确 MC/loader 组合补游戏记录。最后再按主仓规则编译和运行验收。', '',
    '本侧任务不运行 Gradle/游戏，不修改原仓，不提交/推送/发布。当前源码扫描、脚本自测与已有 JAR 检查的结果不能提升为游戏实测结论。', '');
  return out.join('\n');
}
export function parseArgs(argv, allowed = []) {
  const options = { source: DEFAULT_SOURCE, project: PROJECT_ROOT };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (['--source', '--project', '--stage'].includes(arg)) { if (!argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error('Missing value for ' + arg); options[arg.slice(2)] = arg === '--stage' ? argv[++i] : path.resolve(argv[++i]); }
    else if (['--json', '--help', '--self-test', ...allowed].includes(arg)) options[arg.slice(2)] = true;
    else throw new Error('Unknown argument: ' + arg);
  }
  return options;
}
export async function auditSelfTest() {
  const sample = '// Popup() @Composable\n/* Dialog() /* Menu() */ */\nval s = "AnimatedVisibility()"\n@Composable\nprivate fun Real() { DropdownMenu(); animateFloatAsState(1f); UiSounds.play(UiSoundEvent.CLICK) }';
  const sites = scanSurfaces('common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Test.kt', sample);
  assert(sites.some(s => s.kind === 'composable' && s.label === 'Real' && s.line === 4));
  assert(sites.some(s => s.kind === 'menu' && s.label === 'DropdownMenu'));
  assert(sites.some(s => s.kind === 'animation' && s.label === 'animateFloatAsState'));
  assert(!sites.some(s => ['Popup', 'Dialog', 'Menu', 'AnimatedVisibility'].includes(s.label)));
  assert(scanSurfaces('src/main/kotlin/Ui.kt', '@androidx.compose.runtime.Composable\nfun Qualified() {}').some(s => s.kind === 'composable' && s.label === 'Qualified'));
  assert(scanSurfaces('src/main/kotlin/Ui.kt', 'import androidx.compose.runtime.Composable as Render\n@Render\nfun Aliased() {}').some(s => s.kind === 'composable' && s.label === 'Aliased'));
  assert.throws(() => localPath(PROJECT_ROOT, '../outside'));
  assert.throws(() => localPath(PROJECT_ROOT, 'E:/outside'));
  assert.throws(() => lex('/* unfinished'));
  assert.equal(lex('val v = Regex("""viewBox="([^"]+)"""")').filter(t => t.type === 'literal').length, 1);
  assert.equal(TARGETS.length, 14);
  for (const p of ['run/config/accounts.json', 'config/runtime.json', 'src/main/resources/.env', 'src/main/resources/credentials.json', '26.3/build/classes/X.class', 'tools/secret.pem']) assert.equal(fingerprintInput(p), false, p);
  for (const p of ['muoniumconfig-common/src/main/kotlin/Ui.kt', 'muoniumconfig-common/src/main/resources/assets/oneconfig/lang/zh_cn.json', '26.3/gradle.properties']) assert.equal(fingerprintInput(p), true, p);
  assert.equal(hash(Buffer.from([0, 255, 13, 10])), hash(Buffer.from([0, 255, 13, 10])));
  const emptyManifest = { source: { root: DEFAULT_SOURCE, head: 'fixture-head' }, sourceTreeSha256: 'fixture-tree', files: [], uiSources: [], assets: [] };
  const initial = await makeFeatureMap(emptyManifest, PROJECT_ROOT);
  assert(initial.features.every(f => f.status === 'pending'));
  initial.features[0].implementation = [{ path: 'src/main/java/example/Work.java', line: 1, sha256: 'a'.repeat(64) }];
  const retained = await makeFeatureMap(emptyManifest, PROJECT_ROOT, initial);
  assert.equal(retained.features[0].implementation.length, 1);
  const drifted = await makeFeatureMap({ ...emptyManifest, sourceTreeSha256: 'changed-tree' }, PROJECT_ROOT, initial);
  assert.equal(drifted.features[0].status, 'pending');
  assert.equal(drifted.features[0].implementation.length, 0);
  assert(drifted.features[0].invalidatedEvidence);
  initial.features.push({ id: 'removed-source-feature', status: 'pending' });
  assert((await makeFeatureMap(emptyManifest, PROJECT_ROOT, initial)).retiredFeatures.some(f => f.id === 'removed-source-feature'));
  return { ok: true, tests: ['lexer-comments-literals', 'composable-lines', 'qualified-and-aliased-annotations', 'menu-animation-sound-sites', 'path-boundaries', 'malformed-input-fails', 'kotlin-trailing-quote-run', 'fourteen-targets', 'binary-sha256', 'pending-by-default', 'preserve-evidence-on-unchanged-baseline', 'invalidate-on-upstream-drift', 'retain-retired-features', 'no-runtime-or-credential-fingerprints'] };
}
async function assertWritableProject(project, source) {
  const actualProject = await fs.realpath(project), actualSource = await fs.realpath(source);
  const inside = (parent, child) => { const relative = path.relative(parent, child); return !relative || relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative); };
  if (inside(actualSource, actualProject) || inside(actualProject, actualSource)) throw new Error('Writable project and read-only source must be disjoint real directories.');
  for (const directory of ['reference', 'docs']) if (await exists(localPath(project, directory))) {
    const actual = await fs.realpath(localPath(project, directory));
    if (!inside(actualProject, actual)) throw new Error('Output directory escapes project: ' + directory);
  }
  for (const relative of ['reference/source-manifest.json', 'reference/feature-map.json', 'docs/feature-inventory.md']) if (await exists(localPath(project, relative)) && !(await fs.lstat(localPath(project, relative))).isFile()) throw new Error('Output must be a regular local file: ' + relative);
}
async function main() {
  const options = parseArgs(process.argv.slice(2), ['--write']);
  if (options.help) { console.log('Usage: node tools/audit-source.mjs [--source PATH] [--project PATH] [--json] [--write] [--self-test]\nDefault: read-only scan. --write refreshes only the three local reference files; source is always read-only.'); return; }
  if (options['self-test']) { console.log(json(await auditSelfTest())); return; }
  if (options.write) await assertWritableProject(options.project, options.source);
  const manifest = await collectSource(options.source);
  if (options.write) {
    if (path.resolve(options.source) === path.resolve(options.project)) throw new Error('Source and project must differ.');
    const reference = localPath(options.project, 'reference'), docs = localPath(options.project, 'docs');
    const previousPath = localPath(options.project, 'reference/feature-map.json');
    const previous = await exists(previousPath) ? JSON.parse(await fs.readFile(previousPath, 'utf8')) : null;
    const map = await makeFeatureMap(manifest, options.project, previous);
    await fs.mkdir(reference, { recursive: true }); await fs.mkdir(docs, { recursive: true });
    const { runParity } = await import('./check-parity.mjs');
    const report = await runParity({ ...options, baseline: manifest, featureMap: map, current: manifest });
    await fs.writeFile(localPath(options.project, 'reference/source-manifest.json'), json(manifest));
    await fs.writeFile(previousPath, json(map));
    await fs.writeFile(localPath(options.project, 'docs/feature-inventory.md'), renderInventory(manifest, map, report));
    if (!options.json) console.log(json({ written: ['reference/source-manifest.json', 'reference/feature-map.json', 'docs/feature-inventory.md'], sourceHead: manifest.source.head, statistics: manifest.statistics, parity: report.summary, catalogIssues: map.catalogIssues }));
  }
  if (options.json) console.log(json(manifest));
  else if (!options.write) console.log(json({ readOnly: true, sourceHead: manifest.source.head, sourceTreeSha256: manifest.sourceTreeSha256, clean: manifest.source.clean, statistics: manifest.statistics, missingTracked: manifest.missingTracked, note: 'Scan completed. This is not a parity or in-game pass. Use check-parity.mjs for the fail-closed report.' }));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error('audit-source: ' + e.message); process.exitCode = 2; });
