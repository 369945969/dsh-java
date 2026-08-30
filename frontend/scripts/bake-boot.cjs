#!/usr/bin/env node
/**
 * Bake the harness 0.1.2 boot into apps/web's shell index.html at build time,
 * so the static folder is self-contained and servable by dsh-app (Spring Boot)
 * without the live `dsh web` webserver.
 *
 * 0.1.2 changed the boot graph from per-plugin single files to combo batches:
 * the client preloads bootstrap+application batch URLs (`/plugins/??a,b&rev=X`)
 * and lazily fetches per-plugin combo URLs. The batch bytes are concatenations
 * of each plugin's stripped `client.js`. A Java combo controller reconstructs
 * those bytes from the per-plugin files this script writes; the rev/hash is
 * computed identically so advertised URLs match what the browser expects.
 *
 * This script reproduces the harness's offline-equivalent of
 * `ClientModuleRegistry.compose()` + `bootInjections()` + `renderIndexInjections()`:
 *   1. scan every package declaring `dsh.client.platform === 'web'`
 *   2. read each lib/client.js (+ .map), rev = artifactRevision(bundle, map)
 *   3. entries = orderByModuleGraph(rows), bootstrap=[client-modules], application=rest
 *   4. partition each phase into combo batches (URL byte limit), buildBatch → descriptor
 *   5. graph = {rev, entries, batches}; bootInjections(graph) + theme row → index.html
 *   6. write per-plugin client.js(+.map) to <staticDir>/plugins/<id>/
 * Usage: node scripts/bake-boot.cjs <staticDir>
 */
'use strict'

const { createHash, createHmac } = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const FRONTEND_ROOT = path.resolve(__dirname, '..')
const DIST_INDEX = path.join(FRONTEND_ROOT, 'apps', 'web', 'dist', 'index.html')

// Bake ONLY -browse (the <input webkitdirectory> variant); -native needs a node
// FS backend unavailable under static serve, and baking BOTH registers the
// single slot `conversation.hero.workspace.directoryFlow` at priority 0 → conflict.
const EXCLUDE = new Set([
  '@deepseek-ai/dsh-client-ui-directory-picker-native',
  '@deepseek-ai/dsh-experimental-inspector',
])

// Reuse the harness's own built pure functions (exact facade string + renderer
// + graph orderer) so the baked output matches what `dsh web` serves.
const clientModules = require(path.join(FRONTEND_ROOT, 'packages', 'client', 'modules', 'lib', 'index.js'))
const webserver = require(path.join(FRONTEND_ROOT, 'packages', 'host', 'webserver', 'lib', 'index.js'))
const uiTheme = require(path.join(FRONTEND_ROOT, 'packages', 'client', 'ui-theme', 'lib', 'index.js'))
const { bootInjections, orderByModuleGraph } = clientModules
const { renderIndexInjections } = webserver
const DEFAULT_PREFERENCE = uiTheme.DEFAULT_PREFERENCE

if (typeof bootInjections !== 'function') {
  console.error('bake-boot: bootInjections missing from dsh-client-modules lib — run `pnpm run build` first'); process.exit(1)
}
if (typeof orderByModuleGraph !== 'function') {
  console.error('bake-boot: orderByModuleGraph missing from dsh-client-modules lib'); process.exit(1)
}
if (typeof renderIndexInjections !== 'function') {
  console.error('bake-boot: renderIndexInjections missing from dsh-host-webserver lib'); process.exit(1)
}
if (DEFAULT_PREFERENCE === undefined) {
  console.error('bake-boot: DEFAULT_PREFERENCE missing from dsh-client-ui-theme lib'); process.exit(1)
}

// --- constants replicated verbatim from packages/client/modules/src/index.ts ---

const HASH_REVISION_LENGTH = 12
const MAX_COMBO_URL_BYTES = 3 * 1024
const COMBO_REVISION_PLACEHOLDER = '0'.repeat(HASH_REVISION_LENGTH)
const SOURCE_MAP_TRAILER = /(?:\r?\n)?\/\/# sourceMappingURL=[^\r\n]*(?:\r?\n)?$/
const SOURCE_URL_TRAILER = /(?:\r?\n)?\/\/# sourceURL=([^\r\n]+)(?:\r?\n)?$/
const PARSER_PRELOAD_IDS = ['@deepseek-ai/dsh-client-modules']

// --- hash + URL helpers (verbatim from index.ts) ---

function shortHash(input) {
  return createHash('sha1').update(input).digest('hex').slice(0, HASH_REVISION_LENGTH)
}

function framedHash(domain, parts) {
  const hash = createHash('sha1').update(domain).update('\0')
  for (const part of parts) hash.update(`${String(part.byteLength)}:`).update(part)
  return hash.digest('hex').slice(0, HASH_REVISION_LENGTH)
}

function comboUrl(ids, rev, sourceMap = false) {
  const resources = ids.map(id => `${id}/client.js${sourceMap ? '.map' : ''}`).join(',')
  return `/plugins/??${resources}&rev=${rev}`
}

function projectedComboUrlBytes(records) {
  return Buffer.byteLength(comboUrl(
    records.map(record => record.entry.id),
    COMBO_REVISION_PLACEHOLDER,
    true,
  ))
}

function partitionComboRecords(records) {
  const chunks = []
  let current = []
  for (const record of records) {
    const candidate = [...current, record]
    if (projectedComboUrlBytes(candidate) <= MAX_COMBO_URL_BYTES) {
      current = candidate
      continue
    }
    if (current.length === 0) {
      throw new Error(`bake-boot: ${record.entry.id} exceeds the ${String(MAX_COMBO_URL_BYTES)}-byte combo URL limit`)
    }
    chunks.push(current)
    current = [record]
    if (projectedComboUrlBytes(current) > MAX_COMBO_URL_BYTES) {
      throw new Error(`bake-boot: ${record.entry.id} exceeds the ${String(MAX_COMBO_URL_BYTES)}-byte combo URL limit`)
    }
  }
  if (current.length > 0) chunks.push(current)
  return chunks
}

// --- combo source-map assembly (verbatim from index.ts) ---

function newlineCount(value) {
  let count = 0
  for (const char of value) if (char === '\n') count += 1
  return count
}

function comboSource(record) {
  let source = record.bundle.toString('utf8')
  const sourceUrl = SOURCE_URL_TRAILER.exec(source)?.[1]
  source = source.replace(SOURCE_URL_TRAILER, '').replace(SOURCE_MAP_TRAILER, '')
  if (!source.endsWith('\n')) source += '\n'
  const fallbackSource = sourceUrl === undefined
    ? `/plugins/${record.entry.id}/client.js`
    : /^(?:[A-Za-z][A-Za-z\d+.-]*:|\/)/.test(sourceUrl) ? sourceUrl : `/${sourceUrl}`
  return { source, fallbackSource }
}

function comboScript(input, sourceMapUrl) {
  return Buffer.from(sourceMapUrl === undefined ? input : `${input}//# sourceMappingURL=${sourceMapUrl}\n`)
}

function identitySectionMap(source, sourceUrl) {
  const mappings = Array.from({ length: newlineCount(source) }, (_, index) => index === 0 ? 'AAAA' : 'AACA').join(';')
  return { version: 3, names: [], sources: [sourceUrl], sourcesContent: [source], mappings }
}

function comboSectionMap(record) {
  const original = record.sourceMap?.parsed
  if (original === undefined) throw new Error(`bake-boot: source map missing for ${record.entry.id}`)
  const sourcePaths = original.sources
  const sourceRoot = typeof original.sourceRoot === 'string' ? original.sourceRoot : ''
  const base = new URL(`/plugins/${record.entry.id}/client.js.map`, 'http://dsh.invalid')
  const relocated = sourcePaths.map((source) => {
    const separator = sourceRoot !== '' && !sourceRoot.endsWith('/') && !source.startsWith('/') ? '/' : ''
    const resolved = new URL(`${sourceRoot}${separator}${source}`, base)
    return resolved.origin === base.origin
      ? `${resolved.pathname}${resolved.search}${resolved.hash}`
      : resolved.href
  })
  const section = { ...original, sources: relocated }
  delete section.sourceRoot
  return section
}

function buildCombo(records, revision) {
  let source = ''
  const sections = []
  let line = 0
  for (const record of records) {
    const prepared = comboSource(record)
    const section = record.sourceMap === undefined
      ? identitySectionMap(prepared.source, prepared.fallbackSource)
      : comboSectionMap(record)
    sections.push({ offset: { line, column: 0 }, map: section })
    const bundle = `${prepared.source};\n`
    source += bundle
    line += newlineCount(bundle)
  }
  const sourceMap = Buffer.from(`${JSON.stringify({ version: 3, file: 'client.js', sections })}\n`)
  const sourceBytes = Buffer.from(source)
  const rev = revision ?? framedHash('combo', [sourceBytes, sourceMap])
  const entries = records.map(record => record.entry.id)
  const url = comboUrl(entries, rev)
  const sourceMapUrl = comboUrl(entries, rev, true)
  return { url, rev, entries, script: comboScript(source, sourceMapUrl), sourceMap, sourceMapUrl }
}

function buildBatch(phase, records) {
  const artifact = buildCombo(records)
  return { ...artifact, descriptor: { phase, url: artifact.url, rev: artifact.rev, entries: artifact.entries } }
}

function graphRow(id, rev, fields) {
  return {
    id,
    url: comboUrl([id], rev),
    rev,
    ...(fields.inject !== undefined ? { inject: fields.inject } : {}),
    ...(fields.immediately ? { immediately: true } : {}),
    ...(fields.external.length > 0 ? { external: fields.external } : {}),
  }
}

// --- package scan (mirrors ClientModuleRegistry activation, offline) ---

function optionalStringArray(subject, field, value) {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) {
    throw new Error(`bake-boot: ${subject} ${field} must be a string array`)
  }
  return value
}

function parseDshClient(pkgName, value) {
  if (value === undefined) return undefined
  if (typeof value !== 'object' || value === null) {
    throw new Error(`bake-boot: ${pkgName} has a non-object dsh.client declaration`)
  }
  const decl = value
  if (typeof decl.platform !== 'string') {
    throw new Error(`bake-boot: ${pkgName} dsh.client.platform must be a string`)
  }
  const inject = optionalStringArray(pkgName, 'dsh.client.inject', decl.inject)
  const external = optionalStringArray(pkgName, 'dsh.client.external', decl.external)
  if (decl.immediately !== undefined && typeof decl.immediately !== 'boolean') {
    throw new Error(`bake-boot: ${pkgName} dsh.client.immediately must be a boolean`)
  }
  return {
    platform: decl.platform,
    ...(inject !== undefined ? { inject } : {}),
    ...(external !== undefined ? { external } : {}),
    ...(decl.immediately !== undefined ? { immediately: decl.immediately } : {}),
  }
}

function clientExportOf(pkgName, exportsField) {
  if (typeof exportsField !== 'object' || exportsField === null) return undefined
  const client = exportsField['./client']
  if (client === undefined) return undefined
  if (typeof client === 'string') return client
  if (typeof client === 'object' && client !== null) {
    const fallback = client.default
    if (typeof fallback === 'string') return fallback
  }
  throw new Error(`bake-boot: ${pkgName} exports["./client"] must be a string or an object with a string default`)
}

function readSourceMap(clientPath) {
  let body
  try {
    body = fs.readFileSync(`${clientPath}.map`)
  } catch (err) {
    if (err.code !== 'ENOENT') throw err
    return undefined
  }
  const value = JSON.parse(body.toString('utf8'))
  const parsed = typeof value === 'object' && value !== null ? value : undefined
  if (
    parsed === undefined
    || parsed.version !== 3
    || !Array.isArray(parsed.sources)
    || parsed.sources.some(source => typeof source !== 'string')
    || !Array.isArray(parsed.names)
    || parsed.names.some(name => typeof name !== 'string')
    || typeof parsed.mappings !== 'string'
  ) {
    throw new Error(`bake-boot: ${clientPath}.map is not a regular Source Map v3 object`)
  }
  return { body, parsed }
}

function artifactRevision(bundle, sourceMap) {
  return framedHash('plugin-artifact', sourceMap === undefined ? [bundle] : [bundle, sourceMap.body])
}

function scanWebClientPackages() {
  const rows = []
  const stack = [path.join(FRONTEND_ROOT, 'packages')]
  while (stack.length) {
    const dir = stack.pop()
    let entries
    try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      const p = path.join(dir, e.name)
      if (e.isDirectory()) { stack.push(p); continue }
      if (!(e.isFile() && e.name === 'package.json')) continue
      let pkg
      try { pkg = JSON.parse(fs.readFileSync(p, 'utf8')) } catch { continue }
      const decl = parseDshClient(pkg.name, pkg.dsh && pkg.dsh.client)
      if (decl === undefined || decl.platform !== 'web') continue
      if (EXCLUDE.has(pkg.name)) continue
      const clientRel = clientExportOf(pkg.name, pkg.exports)
      if (clientRel === undefined) {
        throw new Error(`bake-boot: ${pkg.name} declares dsh.client but exports no "./client" bundle`)
      }
      const clientPath = path.join(path.dirname(p), clientRel)
      let bundle
      try { bundle = fs.readFileSync(clientPath) }
      catch (err) {
        if (err.code !== 'ENOENT') throw err
        throw new Error(`bake-boot: ${pkg.name} client bundle not built (run \`pnpm run build\`): ${clientPath}`)
      }
      const sourceMap = readSourceMap(clientPath)
      const rev = artifactRevision(bundle, sourceMap)
      rows.push({
        entry: graphRow(pkg.name, rev, {
          ...(decl.inject !== undefined ? { inject: decl.inject } : {}),
          external: decl.external ?? [],
          immediately: decl.immediately === true,
        }),
        bundle,
        sourceMap,
        clientPath,
      })
    }
  }
  return rows
}

// --- ui-theme row (verbatim from packages/client/ui-theme/src/boot-theme.ts) ---

function bootThemeScript(preference) {
  return `(() => {
  const preference = ${JSON.stringify(preference)}
  const systemDark = preference === 'system'
    && typeof matchMedia !== 'undefined'
    && matchMedia('(prefers-color-scheme: dark)').matches
  const dark = preference === 'dark' || systemDark
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
  document.body.toggleAttribute('data-ds-dark-theme', dark)
})()`
}
function bootThemeRow(preference) {
  return { kind: 'script', placement: 'body', text: bootThemeScript(preference) }
}

// --- compose + bake (mirrors ClientModuleRegistry.compose(), offline) ---

function composeGraph(rows) {
  const entries = orderByModuleGraph(rows.map(r => r.entry))
  const table = new Map(rows.map(r => [r.entry.id, r]))
  const bootstrap = PARSER_PRELOAD_IDS
    .map(id => table.get(id))
    .filter(r => r !== undefined)
  const bootstrapIds = new Set(bootstrap.map(r => r.entry.id))
  const application = entries
    .filter(entry => !bootstrapIds.has(entry.id))
    .map(entry => table.get(entry.id))
    .filter(r => r !== undefined)

  const batches = []
  for (const records of partitionComboRecords(bootstrap)) {
    batches.push(buildBatch('bootstrap', records).descriptor)
  }
  for (const records of partitionComboRecords(application)) {
    batches.push(buildBatch('application', records).descriptor)
  }
  return { rev: shortHash(JSON.stringify({ entries, batches })), entries, batches }
}

function main() {
  const staticDir = process.argv[2]
  if (!staticDir) { console.error('bake-boot: usage: node scripts/bake-boot.cjs <staticDir>'); process.exit(2) }
  if (!fs.existsSync(DIST_INDEX)) {
    console.error(`bake-boot: shell index.html not found at ${DIST_INDEX} (run \`pnpm run build\` first)`); process.exit(1)
  }
  const rows = scanWebClientPackages()
  if (rows.length === 0) { console.error('bake-boot: no dsh.client web packages found'); process.exit(1) }
  const graph = composeGraph(rows)
  // <base href="/"> anchors the relative ./assets/* URLs (vite base:'./') at site root.
  const baseHrefRow = { kind: 'html', placement: 'head', html: '<base href="/">' }
  const injectionRows = [baseHrefRow, ...bootInjections(graph), bootThemeRow(DEFAULT_PREFERENCE)]
  const baked = renderIndexInjections(fs.readFileSync(DIST_INDEX, 'utf8'), injectionRows)

  fs.mkdirSync(staticDir, { recursive: true })
  fs.writeFileSync(path.join(staticDir, 'index.html'), baked)

  // Write per-plugin client.js + .map so the Java combo controller can
  // reconstruct batch bytes byte-identically at request time.
  const pluginsDir = path.join(staticDir, 'plugins')
  fs.rmSync(pluginsDir, { recursive: true, force: true })
  let copied = 0
  for (const r of rows) {
    const destDir = path.join(pluginsDir, r.entry.id)
    fs.mkdirSync(destDir, { recursive: true })
    fs.copyFileSync(r.clientPath, path.join(destDir, 'client.js'))
    const map = r.clientPath + '.map'
    if (fs.existsSync(map)) fs.copyFileSync(map, path.join(destDir, 'client.js.map'))
    copied++
  }
  console.log(`bake-boot: baked index.html + ${String(copied)} plugin bundles into ${staticDir} (graph rev ${graph.rev})`)
}

main()
