#!/usr/bin/env node
/**
 * Bake the harness boot into apps/web's shell index.html at build time, so the
 * static folder is self-contained and servable by dsh-app (Spring Boot) without
 * the live `dsh web` webserver.
 *
 * `dsh web` composes the page per request: frontend-static reads dist/index.html,
 * webserver.renderIndex splices injection rows (client-modules' bootInjections +
 * ui-theme's bootThemeInjection) after <head>/<body>, and the /plugins route
 * serves each package's lib/client.js. This script reproduces that offline:
 *   1. scan every package declaring `dsh.client.platform === 'web'`
 *   2. compose the __DSH_BOOT__ entry graph (rev = sha1 of each lib/client.js)
 *   3. reuse the harness's own bootInjections() (exact __ModuleLoader__ facade
 *      string + two parser-blocking preloads + __DSH_BOOT__ global) and
 *      renderIndexInjections() (exact row→HTML renderer), plus the ui-theme
 *      body script
 *   4. write the baked index.html + copy each lib/client.js to
 *      <staticDir>/plugins/<id>/client.js so the served bytes match the revs
 * Usage: node scripts/bake-boot.cjs <staticDir>
 */
'use strict'

const { createHash } = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const FRONTEND_ROOT = path.resolve(__dirname, '..')
const DIST_INDEX = path.join(FRONTEND_ROOT, 'apps', 'web', 'dist', 'index.html')
// Browser plugins `dsh web` mounts dynamically via the directory-picker-auto
// host plugin (which itself needs the Cordis host — absent under dsh-app's
// static serve). Statically baking BOTH -browse and -native makes each register
// the single slot `conversation.hero.workspace.directoryFlow` at priority 0 →
// conflict. Exclude both; the picker surface renders empty (a minor gap) but
// the shell boots clean. Every other dsh.client.platform=web package is baked.
const EXCLUDE = new Set([
  '@deepseek-ai/dsh-client-ui-directory-picker-browse',
  '@deepseek-ai/dsh-client-ui-directory-picker-native',
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

// --- replicated verbatim from packages/client/modules/src/index.ts (pure, stable) ---

/** sha1 content hash shortened to 12 hex chars (bundle rev / graph rev). */
function shortHash(input) {
  return createHash('sha1').update(input).digest('hex').slice(0, 12)
}

/** Graph row for one bundle rev (url carries the rev as cache-busting query). */
function graphRow(id, rev, fields) {
  return {
    id,
    url: `/plugins/${id}/client.js?rev=${rev}`,
    rev,
    ...(fields.inject !== undefined ? { inject: fields.inject } : {}),
    ...(fields.immediately ? { immediately: true } : {}),
    ...(fields.external.length > 0 ? { external: fields.external } : {}),
  }
}

function optionalStringArray(subject, field, value) {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) {
    throw new Error(`bake-boot: ${subject} ${field} must be a string array`)
  }
  return value
}

/** Narrow a parsed `dsh.client` declaration, throwing on malformed fields. */
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

/** Resolve `exports["./client"]` to a relative path (string or one-level conditional). */
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

// --- replicated verbatim from packages/client/ui-theme/src/boot-theme.ts (pure, stable) ---

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

// --- scan + compose (mirrors ClientModuleRegistry activation, offline) ---

/** Walk every package.json under packages/ and collect web-client rows + bundle paths. */
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
      let rev
      try { rev = shortHash(fs.readFileSync(clientPath)) }
      catch (err) {
        if (err.code !== 'ENOENT') throw err
        throw new Error(`bake-boot: ${pkg.name} client bundle not built (run \`pnpm run build\`): ${clientPath}`)
      }
      rows.push({
        entry: graphRow(pkg.name, rev, {
          ...(decl.inject !== undefined ? { inject: decl.inject } : {}),
          external: decl.external ?? [],
          immediately: decl.immediately === true,
        }),
        clientPath,
      })
    }
  }
  return rows
}

function composeGraph(rows) {
  const entries = orderByModuleGraph(rows.map(r => r.entry))
  return { rev: shortHash(JSON.stringify(entries)), entries }
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
  const injectionRows = [...bootInjections(graph), bootThemeRow(DEFAULT_PREFERENCE)]
  const baked = renderIndexInjections(fs.readFileSync(DIST_INDEX, 'utf8'), injectionRows)

  fs.mkdirSync(staticDir, { recursive: true })
  fs.writeFileSync(path.join(staticDir, 'index.html'), baked)

  // Replace plugins/ with freshly built bundles so served bytes match the revs.
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
