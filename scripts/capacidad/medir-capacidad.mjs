#!/usr/bin/env node
/**
 * Medición de capacidad y tiempo de respuesta (issue #9, spec padre #1
 * «Capacidad y respuesta» y «Prueba de capacidad»).
 *
 * Contra un servicio ya iniciado (ver ejecutar-medicion.sh):
 *   1. Semilla: crea SEED asignaciones vigentes por la API pública (mismo
 *      camino transaccional que el uso real; descartado el INSERT directo
 *      porque tendría que replicar AliasSequence y el avance persistido).
 *   2. Calentamiento: WARMUP creaciones + WARMUP resoluciones, no contadas.
 *   3. Creación: REQUESTS solicitudes POST /api/links con CLIENTS clientes
 *      concurrentes (cada cliente emite la siguiente al completar la
 *      anterior, como un cliente real).
 *   4. Resolución: REQUESTS solicitudes GET /{alias} con CLIENTS clientes,
 *      SIN seguir redirecciones: se mide hasta la respuesta del acortador.
 *   5. Invariantes: unicidad de alias emitidos, coherencia de Location con
 *      el destino registrado, persistencia (muestra re-resuelta después de
 *      la carga), alias jamás emitido -> 404, y una creación adicional por
 *      encima del volumen de referencia -> 201.
 *
 * Una respuesta con estado inesperado NO cuenta como éxito aunque sea
 * rápida: el criterio estricto es ok_within_1s / requests.
 *
 * Salida (en --out):
 *   creaciones.csv / resoluciones.csv  — una fila por solicitud (todas las
 *                                        fases, columna `phase` distingue).
 *   resumen.json                       — percentiles, errores, invariantes,
 *                                        veredicto y estadísticas de la base.
 *
 * Uso:
 *   node medir-capacidad.mjs --base http://192.168.1.7:8080 --out DIR
 * Env adicional: PUBLIC_BASE_URL (verifica shortUrl), DB_CONTAINER
 * (nombre de contenedor postgres para estadísticas vía docker exec psql).
 */
import http from 'node:http';
import { performance } from 'node:perf_hooks';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
  options: {
    base: { type: 'string' },
    out: { type: 'string', default: '.' },
    seed: { type: 'string', default: '1000' },
    requests: { type: 'string', default: '1000' },
    warmup: { type: 'string', default: '200' },
    clients: { type: 'string', default: '10' },
    sample: { type: 'string', default: '300' },
  },
});

if (!args.base) {
  console.error('Falta --base <url> (dirección medida del servicio)');
  process.exit(64);
}

const BASE = new URL(args.base);
const OUT_DIR = args.out;
const SEED = Number(args.seed);
const REQUESTS = Number(args.requests);
const WARMUP = Number(args.warmup);
const CLIENTS = Number(args.clients);
const SAMPLE = Number(args.sample);
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL ?? '').replace(/\/$/, '');
const DB_CONTAINER = process.env.DB_CONTAINER ?? '';
const TIMEOUT_MS = 15_000;
const LIMIT_MS = 1_000;

const agent = new http.Agent({ keepAlive: true, maxSockets: CLIENTS });
const createRows = [];
const resolveRows = [];
const knownDestinations = new Map(); // alias -> destino registrado
const emittedAliases = new Set();
let duplicateAliases = 0;
const runStart = performance.now();

function httpRequest(method, urlPath, jsonBody) {
  return new Promise((resolve) => {
    const t0 = performance.now();
    const payload = jsonBody === undefined ? null : JSON.stringify(jsonBody);
    const req = http.request(
      {
        hostname: BASE.hostname,
        port: BASE.port,
        path: urlPath,
        method,
        agent,
        headers: {
          ...(payload ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } : {}),
          accept: '*/*',
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () =>
          resolve({
            ok: true,
            t0,
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(chunks).toString('utf8'),
            ms: performance.now() - t0,
          }));
      },
    );
    req.setTimeout(TIMEOUT_MS, () => req.destroy(new Error(`timeout ${TIMEOUT_MS}ms`)));
    req.on('error', (e) =>
      resolve({ ok: false, t0, status: 0, headers: {}, body: '', ms: performance.now() - t0, error: String(e.code || e.message || e) }));
    if (payload) req.write(payload);
    req.end();
  });
}

/** CLIENTS trabajadores sobre un contador compartido: modelo cerrado. */
async function runPool(total, op) {
  let next = 0;
  const workers = Array.from({ length: CLIENTS }, async (_, w) => {
    for (;;) {
      const seq = next++;
      if (seq >= total) return;
      await op(seq, w);
    }
  });
  await Promise.all(workers);
}

function recordRow(rows, { t0, ms, ...rest }) {
  rows.push({ ...rest, start_ms: (t0 - runStart).toFixed(3), duration_ms: ms.toFixed(3) });
}

async function opCreate(phase, seq, worker, destination) {
  const r = await httpRequest('POST', '/api/links', { destination });
  let alias = '';
  let verification = 'fail';
  let error = r.error ?? '';
  if (r.status === 201) {
    try {
      const body = JSON.parse(r.body);
      alias = body.alias ?? '';
      if (PUBLIC_BASE_URL && body.shortUrl !== `${PUBLIC_BASE_URL}/${alias}`) {
        error = `shortUrl inesperado: ${body.shortUrl}`;
      } else if (emittedAliases.has(alias)) {
        duplicateAliases += 1;
        error = `alias duplicado: ${alias}`;
      } else {
        emittedAliases.add(alias);
        knownDestinations.set(alias, destination);
        verification = 'ok';
      }
    } catch (e) {
      error = `JSON inválido: ${e.message}`;
    }
  } else {
    error = error || `status ${r.status}: ${r.body.slice(0, 120)}`;
  }
  recordRow(createRows, {
    phase, seq, worker, t0: r.t0, ms: r.ms,
    status: r.status, alias, expected_destination: destination,
    location: '', verification, error,
  });
  return r.status === 201 && verification === 'ok';
}

async function opResolve(phase, seq, worker, pool) {
  const alias = pool[Math.floor(Math.random() * pool.length)];
  const expected = knownDestinations.get(alias);
  const r = await httpRequest('GET', `/${alias}`);
  const location = r.headers.location ?? '';
  const verification = r.status === 302 && location === expected ? 'ok' : 'fail';
  recordRow(resolveRows, {
    phase, seq, worker, t0: r.t0, ms: r.ms,
    status: r.status, alias, expected_destination: expected,
    location, verification,
    error: r.error ?? (verification === 'ok' ? '' : `status ${r.status} location ${location || '(vacío)'}`),
  });
  return verification === 'ok';
}

function summarize(rows) {
  const durations = rows.map((r) => Number(r.duration_ms)).sort((a, b) => a - b);
  const n = durations.length;
  const pct = (p) => (n ? durations[Math.ceil((p / 100) * n) - 1] : 0);
  const errors = rows.filter((r) => r.verification !== 'ok').length;
  const withinLimit = durations.filter((d) => d <= LIMIT_MS).length;
  const okWithinLimit = rows.filter((r) => r.verification === 'ok' && Number(r.duration_ms) <= LIMIT_MS).length;
  const byStatus = {};
  for (const r of rows) byStatus[r.status] = (byStatus[r.status] ?? 0) + 1;
  return {
    requests: n,
    errors,
    by_status: byStatus,
    min_ms: n ? durations[0] : 0,
    p50_ms: pct(50),
    p95_ms: pct(95),
    p99_ms: pct(99),
    max_ms: n ? durations[n - 1] : 0,
    mean_ms: n ? Number((durations.reduce((a, d) => a + d, 0) / n).toFixed(3)) : 0,
    within_1s: withinLimit,
    pct_within_1s: n ? Number(((withinLimit / n) * 100).toFixed(2)) : 0,
    ok_within_1s: okWithinLimit,
    pct_ok_within_1s: n ? Number(((okWithinLimit / n) * 100).toFixed(2)) : 0,
  };
}

function dbStats(container) {
  if (!container) return null;
  try {
    return dbStatsUnsafe(container);
  } catch (e) {
    console.error(`Aviso: no se pudieron leer estadísticas de la base: ${e.message}`);
    return null;
  }
}

function dbStatsUnsafe(container) {
  const q = (sql) =>
    execFileSync('docker', ['exec', container, 'psql', '-U', 'acortador', '-d', 'acortador', '-tA', '-c', sql], {
      encoding: 'utf8',
    }).trim();
  return {
    assignments_total: Number(q('select count(*) from asignacion')),
    aliases_total: Number(q('select count(*) from alias')),
    active_assignments: Number(q(`select count(*) from alias al
      join asignacion a on a.alias_codigo = al.codigo and a.id = al.asignacion_actual_id
      where a.vence_en > now()`)),
    asignacion_size: q(`select pg_size_pretty(pg_total_relation_size('asignacion'))`),
    alias_size: q(`select pg_size_pretty(pg_total_relation_size('alias'))`),
    proximo_indice: Number(q('select proximo_indice from generador_alias')),
  };
}

function writeCsv(file, rows) {
  const cols = ['phase', 'seq', 'worker', 'start_ms', 'duration_ms', 'status', 'alias', 'expected_destination', 'location', 'verification', 'error'];
  const esc = (v) => (/[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));
  const lines = [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c] ?? '')).join(','))];
  fs.writeFileSync(file, lines.join('\n') + '\n');
}

function log(msg) {
  console.log(`[${((performance.now() - runStart) / 1000).toFixed(1)}s] ${msg}`);
}

// --- Fases -----------------------------------------------------------------

fs.mkdirSync(OUT_DIR, { recursive: true });
log(`Base medida: ${BASE.origin} | clients=${CLIENTS} seed=${SEED} requests=${REQUESTS} warmup=${WARMUP}`);

let seedFailures = 0;

log('Semilla: creando asignaciones vigentes por la API...');
await runPool(SEED, async (seq, w) => {
  const ok = await opCreate('seed', seq, w, `https://semilla-${seq}.example.com/documento/${seq}?origen=capacidad`);
  if (!ok) seedFailures += 1;
});
if (seedFailures > 0) {
  console.error(`Semilla incompleta: ${seedFailures} creaciones fallaron. Abortando.`);
  process.exit(1);
}
const statsAfterSeed = dbStats(DB_CONTAINER);
log(`Semilla completa: ${knownDestinations.size} vigentes. DB: ${JSON.stringify(statsAfterSeed ?? 'n/d')}`);

log('Calentamiento...');
await runPool(WARMUP, (seq, w) => opCreate('warmup', seq, w, `https://warmup-${seq}.example.com/r/${seq}`));
const warmupPool = [...knownDestinations.keys()];
await runPool(WARMUP, (seq, w) => opResolve('warmup', seq, w, warmupPool));
log(`Calentamiento completo (${WARMUP} creaciones + ${WARMUP} resoluciones).`);

log('Midiendo CREACIÓN...');
await runPool(REQUESTS, (seq, w) => opCreate('create', seq, w, `https://carga-${seq}.example.com/recurso/${seq}?x=1&y=2`));
log('Midiendo RESOLUCIÓN (sin seguir redirects)...');
const resolvePool = [...knownDestinations.keys()];
await runPool(REQUESTS, (seq, w) => opResolve('resolve', seq, w, resolvePool));

// --- Invariantes posteriores a la carga ------------------------------------

log('Verificando persistencia: re-resolución de muestra...');
const step = Math.max(1, Math.floor(resolvePool.length / SAMPLE));
const sample = resolvePool.filter((_, i) => i % step === 0).slice(0, SAMPLE);
let persistenceFailures = 0;
for (const alias of sample) {
  const r = await httpRequest('GET', `/${alias}`);
  if (!(r.status === 302 && r.headers.location === knownDestinations.get(alias))) persistenceFailures += 1;
}

log('Verificando alias jamás emitido (404)...');
let notFoundPageOk = true;
for (const alias of ['zzzzzz', '99999', 'yyyyyyyy']) {
  const r = await httpRequest('GET', `/${alias}`);
  if (r.status !== 404 || !r.body.includes('Este enlace no existe o venció')) notFoundPageOk = false;
}

log('Creación adicional por encima del volumen de referencia...');
const extra = await httpRequest('POST', '/api/links', { destination: 'https://posterior-al-volumen.example.com/x' });
const createBeyondReferenceOk = extra.status === 201;
if (createBeyondReferenceOk) {
  const body = JSON.parse(extra.body);
  knownDestinations.set(body.alias, 'https://posterior-al-volumen.example.com/x');
  emittedAliases.add(body.alias);
}

const statsFinal = dbStats(DB_CONTAINER);

// --- Resumen ----------------------------------------------------------------

const createStats = summarize(createRows.filter((r) => r.phase === 'create'));
const resolveStats = summarize(resolveRows.filter((r) => r.phase === 'resolve'));

const invariants = {
  unique_aliases: duplicateAliases === 0,
  resolve_coherence: resolveStats.errors === 0,
  persistence_sample: { sample: sample.length, failures: persistenceFailures, ok: persistenceFailures === 0 },
  unknown_alias_404: notFoundPageOk,
  create_beyond_reference_201: createBeyondReferenceOk,
};

const passed =
  createStats.pct_ok_within_1s >= 95 &&
  resolveStats.pct_ok_within_1s >= 95 &&
  createStats.errors === 0 &&
  resolveStats.errors === 0 &&
  invariants.unique_aliases &&
  invariants.persistence_sample.ok &&
  invariants.unknown_alias_404 &&
  invariants.create_beyond_reference_201;

const summary = {
  generated_at: new Date().toISOString(),
  measured_base: BASE.origin,
  public_base_url: PUBLIC_BASE_URL || null,
  parameters: { clients: CLIENTS, seed: SEED, requests: REQUESTS, warmup: WARMUP, limit_ms: LIMIT_MS },
  create: createStats,
  resolve: resolveStats,
  invariants,
  db_after_seed: statsAfterSeed,
  db_final: statsFinal,
  verdict: passed ? 'PASS' : 'FAIL',
};

writeCsv(path.join(OUT_DIR, 'creaciones.csv'), createRows);
writeCsv(path.join(OUT_DIR, 'resoluciones.csv'), resolveRows);
fs.writeFileSync(path.join(OUT_DIR, 'resumen.json'), JSON.stringify(summary, null, 2) + '\n');

console.log('\n================ RESUMEN ================');
console.log(JSON.stringify(summary, null, 2));
console.log(`Veredicto: ${summary.verdict === 'PASS' ? 'CUMPLE' : 'NO CUMPLE'}`);
process.exit(passed ? 0 : 1);
