#!/usr/bin/env node
/**
 * Recorrido integrado de la demo de etapa 1 (issue #11, spec padre #1).
 *
 * Ejercita de punta a punta el servicio levantado por
 * ejecutar-demo.sh, sin simulaciones: HTTP real contra la API pública,
 * PNG de QR generado con el mismo módulo que sirve la web
 * (src/main/resources/static/qr-code.js + vendor/qrcode.js) y
 * decodificado con bibliotecas independientes (UPNG.js + jsQR, las de
 * src/test/js) y con zbarimg si está instalado. El historial se consulta
 * por psql dentro del contenedor PostgreSQL de la demo.
 *
 * Fases (una invocación por fase; el orquestador reinicia el servicio
 * entre ellas sobre la MISMA base, como un reinicio real):
 *
 *   a) servicio con la duración de entrega (60 min): web servida,
 *      creación con resultado completo (enlace + vencimiento + aviso),
 *      QR PNG verificado por decodificación, redirección 302, destinos
 *      locales/credenciales/parámetros/fragmentos conservados verbatim,
 *      rechazos del contrato (incluidos orígenes propios equivalentes) y
 *      alias desconocido -> 404 con la página acordada.
 *   b) servicio reiniciado con LINK_DURATION corto (configurable, por
 *      defecto PT1M; mismo mecanismo que el experimento del issue #10):
 *      continuidad tras reinicio, vencimiento real sin renovación por
 *      visitas, reciclaje del alias vencido con historial conservado, un
 *      QR viejo resolviendo al nuevo destino, y preferencia de reciclaje
 *      por los alias más cortos aun habiendo vencidos más largos.
 *   c) servicio reiniciado otra vez con 60 min (restitución de la
 *      entrega): continuidad del enlace de la fase a), nueva creación
 *      con vencimiento de 60 minutos y estado final de la base.
 *
 * Lo que NO se puede automatizar (clics en navegador real, instalación
 * manual de complementos, escaneo con un celular) queda como checklist
 * para el humano en docs/demo-etapa-1.md.
 *
 * Uso:
 *   node recorrido-demo.mjs --phase a|b|c --base http://<ip>:<puerto> --out DIR
 * Env: PUBLIC_BASE_URL (verifica shortUrl), DB_CONTAINER (consultas psql
 * vía docker exec), DEMO_EXPECTED_DURATION_MIN (60 en a/c; el de la
 * duración corta en b).
 */
import { parseArgs } from 'node:util';
import { execFileSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import QrPng from '../../src/main/resources/static/qr-code.js';
import qrcodeFactory from '../../src/main/resources/static/vendor/qrcode.js';
import { decodePngToRgba, decodeQrText } from '../../src/test/js/png-decode.mjs';

const { values: args } = parseArgs({
  options: {
    phase: { type: 'string' },
    base: { type: 'string' },
    out: { type: 'string', default: '.' },
  },
});

if (!args.phase || !args.base) {
  console.error('Falta --phase a|b|c y/o --base <url>');
  process.exit(64);
}

const PHASE = args.phase;
const BASE = args.base.replace(/\/$/, '');
const OUT_DIR = args.out;
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL ?? '').replace(/\/$/, '');
const DB_CONTAINER = process.env.DB_CONTAINER ?? '';
const EXPECTED_MIN = Number(process.env.DEMO_EXPECTED_DURATION_MIN ?? '60');
const STATE_FILE = path.join(OUT_DIR, 'estado.json');
const NOT_FOUND_TEXT = 'Este enlace no existe o venció';
const REUSE_TEXT = 'reutilizarse';

let failures = 0;
let checks = 0;

function check(name, cond, detail = '') {
  checks++;
  if (cond) {
    console.log(`  [OK] ${name}${detail ? ` — ${detail}` : ''}`);
  } else {
    failures++;
    console.log(`  [FALLA] ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return {}; }
}
function saveState(patch) {
  const state = { ...loadState(), ...patch };
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
  return state;
}

const agent = new http.Agent({ keepAlive: true });

// HTTP crudo con node:http — el cliente NUNCA sigue redirecciones, igual
// que la medición de capacidad: el 302 y su Location se verifican tal
// cual los emite el acortador.
function httpRequest(method, urlPath, rawBody, headers = {}) {
  return new Promise((resolve) => {
    const url = new URL(urlPath, BASE);
    const req = http.request({
      hostname: url.hostname,
      port: url.port,
      path: url.pathname + url.search,
      method,
      agent,
      headers,
      timeout: 15_000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try { json = JSON.parse(text); } catch { /* no JSON */ }
        resolve({
          status: res.statusCode,
          headers: res.headers,
          location: res.headers.location ?? null,
          text,
          json,
        });
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (e) => resolve({ status: 0, headers: {}, location: null, text: '', json: null, error: e }));
    if (rawBody !== undefined) req.write(rawBody);
    req.end();
  });
}

function createLink(destination) {
  return httpRequest('POST', '/api/links', JSON.stringify({ destination }),
    { 'Content-Type': 'application/json' })
    .then((r) => ({ res: r, body: r.json }));
}

function createRaw(rawBody, contentType = 'application/json') {
  return httpRequest('POST', '/api/links', rawBody, { 'Content-Type': contentType })
    .then((r) => ({ res: r, body: r.json }));
}

function resolveAlias(alias) {
  return httpRequest('GET', `/${alias}`)
    .then((r) => ({ res: r, body: r.text, location: r.location }));
}

function get(pathname) {
  return httpRequest('GET', pathname);
}

function psql(sql) {
  if (!DB_CONTAINER) return null;
  return execFileSync('docker', [
    'exec', DB_CONTAINER, 'psql', '-U', 'acortador', '-d', 'acortador', '-tAc', sql,
  ], { encoding: 'utf8' }).trim();
}

// vence_en de la asignación actual de un alias, en epoch ms (sin parsear
// el texto de fecha de psql).
function venceEnMs(alias) {
  const out = psql(`select extract(epoch from a.vence_en) * 1000 from asignacion a
    join alias al on al.codigo = a.alias_codigo and al.asignacion_actual_id = a.id
    where al.codigo = '${alias}'`);
  return out === null || out === '' ? null : Number(out);
}

const zbarimgAvailable = (() => {
  try { execFileSync('zbarimg', ['--version'], { stdio: 'pipe' }); return true; }
  catch { return false; }
})();

// Genera el PNG del QR igual que la web (mismo módulo, mismos bytes que
// descargaría el usuario), lo guarda en OUT_DIR y lo decodifica con
// UPNG+jsQR y con zbarimg si está disponible.
function verificarQr(shortUrl, alias) {
  const png = QrPng.encode(qrcodeFactory, shortUrl);
  const file = path.join(OUT_DIR, QrPng.suggestedFileName(alias));
  fs.writeFileSync(file, png);

  const { rgba, width, height } = decodePngToRgba(png);
  check(`QR de ${alias}: PNG decodifica exactamente el shortUrl (UPNG+jsQR)`,
    decodeQrText(rgba, width, height) === shortUrl, shortUrl);

  if (zbarimgAvailable) {
    try {
      const out = execFileSync('zbarimg', ['--raw', '-q', file], { encoding: 'utf8' }).trim();
      check(`QR de ${alias}: zbarimg decodifica el shortUrl`, out === shortUrl, out);
    } catch (e) {
      check(`QR de ${alias}: zbarimg decodifica el shortUrl`, false, e.message);
    }
  }
  return file;
}

function expiresAtOk(expiresAt, expectedMin) {
  const ms = Date.parse(expiresAt) - Date.now();
  return Math.abs(ms - expectedMin * 60_000) < 10_000;
}

// Espera (en tiempo real) a que la asignación venza y verifica el 404.
async function waitExpired(alias, expiresAtIso, margenMs = 3_000, deadlineMs = 180_000) {
  const target = Date.parse(expiresAtIso) + margenMs;
  const wait = target - Date.now();
  if (wait > 0) {
    console.log(`  … esperando vencimiento real de ${alias} (${Math.round(wait / 1000)} s)`);
    await sleep(wait);
  }
  const deadline = Date.now() + deadlineMs;
  for (;;) {
    const { res, body } = await resolveAlias(alias);
    if (res.status === 404) return { res, body };
    if (Date.now() > deadline) return { res, body };
    await sleep(2_000);
  }
}

// ---------------------------------------------------------------- fase a

async function phaseA() {
  console.log('== Fase A — entorno documentado, duración de entrega 60 min ==');

  // Web servida por el backend
  const home = await get('/');
  check('GET / sirve la web (200)', home.status === 200);
  check('La web referencia el formulario y el stack QR',
    home.text.includes('shorten-form') && home.text.includes('qr-code.js')
    && home.text.includes('vendor/qrcode.js') && home.text.includes('qr-download'));
  for (const asset of ['app.js', 'qr-code.js', 'styles.css', 'vendor/qrcode.js']) {
    const r = await get(`/${asset}`);
    check(`Recurso estático servido: /${asset}`, r.status === 200);
  }

  // Creación con el resultado completo del contrato
  const destinoA = 'https://docs.ejemplo.com/carpeta%20compartida/informe?a=1&b=2#seccion-3';
  const { res: cA, body: bA } = await createLink(destinoA);
  check('POST /api/links -> 201', cA.status === 201);
  check('201 con Cache-Control: no-store', cA.headers['cache-control'] === 'no-store');
  check('Contrato completo: shortUrl, alias, expiresAt, reuseNotice',
    !!(bA && bA.shortUrl && bA.alias && bA.expiresAt && bA.reuseNotice));
  check('reuseNotice avisa la posible reutilización del alias',
    !!bA && String(bA.reuseNotice).includes(REUSE_TEXT), bA?.reuseNotice);
  if (PUBLIC_BASE_URL) {
    check('shortUrl usa la dirección pública LAN configurada',
      !!bA && bA.shortUrl === `${PUBLIC_BASE_URL}/${bA.alias}`, bA?.shortUrl);
  }
  check('expiresAt ≈ ahora + 60 min', !!bA && expiresAtOk(bA.expiresAt, 60), bA?.expiresAt);

  const aliasA = bA?.alias ?? 'noemitido';
  saveState({ aliasA, destinoA, expiresAtA: bA?.expiresAt, shortUrlA: bA?.shortUrl });
  if (bA?.shortUrl) verificarQr(bA.shortUrl, aliasA);

  // Redirección 302 al destino verbatim
  const { res: rA, location } = await resolveAlias(aliasA);
  check(`GET /${aliasA} -> 302`, rA.status === 302);
  check('302 con Location al destino verbatim (parámetros y fragmento conservados)',
    location === destinoA, location);
  check('302 con Cache-Control: no-store', rA.headers['cache-control'] === 'no-store');

  // Destinos del contrato: LAN, credenciales, límite 8.192
  const aceptados = [
    ['destino LAN', 'http://192.168.1.99:9000/carpeta%20compartida/recurso'],
    ['credenciales incrustadas', 'http://usuario:clave@192.168.1.99:9000/privado?x=1'],
    ['punycode + percent-encoding UTF-8', 'https://xn--bcher-kva.ch/secci%C3%B3n?nombre=jos%C3%A9'],
    ['puerto explícito y query repetida', 'https://ejemplo.com:8443/p?tag=a&tag=b'],
  ];
  for (const [nombre, dest] of aceptados) {
    const { res, body } = await createLink(dest);
    check(`Acepta ${nombre}`, res.status === 201, body?.alias);
    if (res.status === 201) {
      const { res: rr, location: loc } = await resolveAlias(body.alias);
      check(`  … y resuelve verbatim (${nombre})`, rr.status === 302 && loc === dest);
    }
  }
  const destinoMax = `https://ejemplo.com/${'a'.repeat(8192 - 'https://ejemplo.com/'.length)}`;
  const { res: cMax } = await createLink(destinoMax);
  check('Acepta destino de exactamente 8.192 caracteres', cMax.status === 201);

  // Rechazos del contrato (sin crear asignación ni consumir alias)
  const aliasAntes = psql('select count(*) from alias');
  const indiceAntes = psql('select proximo_indice from generador_alias');
  const rechazos = [
    ['vacío', '', 'EMPTY_DESTINATION'],
    ['esquema no admitido', 'ftp://ejemplo.com/x', 'UNSUPPORTED_SCHEME'],
    ['sin esquema', 'ejemplo.com/solo-ruta', 'UNSUPPORTED_SCHEME'],
    ['espacio sin codificar', 'http://ejemplo.com/mi archivo', 'MALFORMED_DESTINATION'],
    ['escape % inválido', 'http://ejemplo.com/%zz', 'MALFORMED_DESTINATION'],
    ['unicode en crudo', 'https://ejemplo.com/café', 'MALFORMED_DESTINATION'],
    ['más de 8.192 caracteres', `https://ejemplo.com/${'a'.repeat(8193 - 20)}`, 'DESTINATION_TOO_LONG'],
    ['origen propio loopback', `http://localhost:${new URL(BASE).port}/x`, 'OWN_ORIGIN'],
    ['origen propio 127.0.0.1', `http://127.0.0.1:${new URL(BASE).port}/x`, 'OWN_ORIGIN'],
    ['origen propio público LAN', `${PUBLIC_BASE_URL}/x`, 'OWN_ORIGIN'],
  ];
  for (const extra of (process.env.DEMO_EXTRA_OWN_ORIGINS ?? '').split(',').filter(Boolean)) {
    rechazos.push([`origen propio configurado (${extra})`, `${extra}/x`, 'OWN_ORIGIN']);
  }
  for (const [nombre, dest, codigo] of rechazos) {
    const { res, body } = await createLink(dest);
    check(`Rechaza ${nombre} -> 400 ${codigo}`,
      res.status === 400 && body?.error === codigo,
      body ? `${body.error}: ${body.message}` : `HTTP ${res.status}`);
  }
  const { res: cJson, body: bJson } = await createRaw('{"destino":"https://x.com"}');
  check('Propiedad desconocida -> 400 INVALID_REQUEST',
    cJson.status === 400 && bJson?.error === 'INVALID_REQUEST');
  const { res: cBad, body: bBad } = await createRaw('{no es json');
  check('JSON inválido -> 400 INVALID_REQUEST',
    cBad.status === 400 && bBad?.error === 'INVALID_REQUEST');

  check('Ningún rechazo creó alias ni consumió el generador',
    psql('select count(*) from alias') === aliasAntes
    && psql('select proximo_indice from generador_alias') === indiceAntes,
    `alias=${aliasAntes}, proximo_indice=${indiceAntes}`);

  // Alias desconocido -> 404 con la página acordada
  const { res: u, body: uBody } = await resolveAlias('9zZxY');
  check('Alias jamás emitido -> 404', u.status === 404);
  check('404 con la página «Este enlace no existe o venció»',
    uBody.includes(NOT_FOUND_TEXT));
  check('404 con Cache-Control: no-store', u.headers['cache-control'] === 'no-store');
}

// ---------------------------------------------------------------- fase b

async function phaseB() {
  const state = loadState();
  console.log('== Fase B — reinicio con duración corta: vencimiento, reciclaje y QR viejo ==');
  console.log(`   (duración configurada en este arranque: ${EXPECTED_MIN} min; la entrega sigue siendo 60 min)`);

  // Continuidad tras reinicio: el enlace de la fase A sigue resolviendo
  const { res: rA, location } = await resolveAlias(state.aliasA);
  check(`Tras reinicio, /${state.aliasA} sigue 302`, rA.status === 302);
  check('… al destino original conservado', location === state.destinoA);
  const venceA = venceEnMs(state.aliasA);
  check('Su vencimiento persistido no se recalculó con la nueva duración',
    venceA !== null && Math.abs(venceA - Date.parse(state.expiresAtA)) < 2_000,
    `vence_en_ms=${venceA}, expiresAt=${state.expiresAtA}`);

  // Enlace de corta duración + su QR
  const destinoS = 'https://laboratorio.ejemplo.com/demo-vencimiento';
  const { res: cS, body: bS } = await createLink(destinoS);
  check('Creación de corta duración -> 201', cS.status === 201);
  check(`expiresAt ≈ ahora + ${EXPECTED_MIN} min`, !!bS && expiresAtOk(bS.expiresAt, EXPECTED_MIN), bS?.expiresAt);
  const aliasS = bS?.alias ?? 'sinalias';
  const shortUrlS = bS?.shortUrl;
  saveState({ aliasS, destinoS, expiresAtS: bS?.expiresAt, shortUrlS });
  if (bS?.shortUrl) verificarQr(bS.shortUrl, aliasS);

  const { res: rS, location: locS } = await resolveAlias(aliasS);
  check(`/${aliasS} vigente -> 302 al destino`, rS.status === 302 && locS === destinoS);

  // Las visitas no renuevan: vence_en no cambia tras varias resoluciones
  const venceAntes = venceEnMs(aliasS);
  for (let i = 0; i < 3; i++) await resolveAlias(aliasS);
  const venceDespues = venceEnMs(aliasS);
  check('Visitar no renueva el vencimiento (vence_en estable tras 3 visitas)',
    venceAntes !== null && venceAntes === venceDespues, `vence_en_ms=${venceDespues}`);

  // Vencimiento real: esperar a que pase su instante y ver el 404
  const { res: vRes, body: vBody } = await waitExpired(
    aliasS, bS?.expiresAt ?? new Date().toISOString());
  check(`/${aliasS} vencido -> 404`, vRes.status === 404);
  check('404 de vencido con la página acordada', vBody.includes(NOT_FOUND_TEXT));
  check('404 de vencido con Cache-Control: no-store',
    vRes.headers['cache-control'] === 'no-store');

  // Reciclaje: el único alias vencido debe ser reutilizado
  const destinoNuevo = 'https://laboratorio.ejemplo.com/destino-nuevo';
  const { res: cR, body: bR } = await createLink(destinoNuevo);
  check('La creación siguiente recicla el alias vencido',
    cR.status === 201 && bR?.alias === aliasS,
    `esperado=${aliasS}, obtenido=${bR?.alias}`);
  const { res: rR, location: locR } = await resolveAlias(aliasS);
  check('El alias reciclado resuelve al NUEVO destino',
    rR.status === 302 && locR === destinoNuevo, locR);

  // El QR viejo (guardado como PNG) sigue codificando el mismo enlace, que
  // ahora conduce a la nueva asignación — consecuencia aceptada del contrato.
  const pngViejo = path.join(OUT_DIR, QrPng.suggestedFileName(aliasS));
  const { rgba, width, height } = decodePngToRgba(fs.readFileSync(pngViejo));
  check('El QR viejo sigue decodificando el mismo enlace público',
    decodeQrText(rgba, width, height) === shortUrlS, shortUrlS);
  check('… y ese enlace ahora conduce a la asignación nueva',
    locR === destinoNuevo, `${shortUrlS} -> ${locR}`);

  // Historial conservado: ambas asignaciones del alias reciclado
  const historial = psql(`select id || '|' || destino || '|' || vence_en
    from asignacion where alias_codigo = '${aliasS}' order by id`);
  const filas = historial ? historial.split('\n') : [];
  check(`Historial del alias ${aliasS}: conserva ambas asignaciones`,
    filas.length === 2, filas.map((f) => f.split('|')[0]).join(', '));
  check('La asignación anterior conserva su destino original',
    filas.length === 2 && filas[0].includes(destinoS));

  // Preferencia por los más cortos: emitir hasta que aparezca un alias de
  // dos caracteres, dejar vencer todo el grupo y comprobar que la próxima
  // creación reutiliza un alias de UN carácter aun habiendo un vencido de dos.
  console.log('  … emitiendo enlaces hasta agotar los alias de un carácter');
  let aliasDos = null;
  let ultimoVence = bR?.expiresAt ?? new Date(Date.now() + 60_000).toISOString();
  const emitidos = new Set();
  let creados = 0;
  for (let i = 0; i < 80 && !aliasDos; i++) {
    const { res, body } = await createLink(`https://relleno.ejemplo.com/${i}`);
    if (res.status !== 201) { check('Emisión de relleno sin errores', false, `HTTP ${res.status}`); break; }
    creados++;
    emitidos.add(body.alias);
    ultimoVence = body.expiresAt;
    if (body.alias.length === 2) aliasDos = body.alias;
  }
  check('Se emitió un alias de dos caracteres tras agotar los de uno',
    !!aliasDos, aliasDos ?? 'no se alcanzó');
  check('Sin alias duplicados en la emisión', emitidos.size === creados,
    `${creados} creaciones, ${emitidos.size} alias distintos`);

  const { res: vDos } = await waitExpired(aliasDos ?? 'xx', ultimoVence);
  check(`El grupo de corta duración venció (${aliasDos} -> 404)`, vDos.status === 404);
  const { res: cP, body: bP } = await createLink('https://laboratorio.ejemplo.com/prefiere-cortos');
  check('Con vencidos de 1 y 2 caracteres, se reutiliza uno de 1',
    cP.status === 201 && bP?.alias?.length === 1 && bP.alias !== aliasDos,
    `elegido=${bP?.alias}, vencido de dos=${aliasDos}`);
  saveState({ aliasDos });
}

// ---------------------------------------------------------------- fase c

async function phaseC() {
  const state = loadState();
  console.log('== Fase C — restitución a 60 min y continuidad final ==');

  const { res: rA, location } = await resolveAlias(state.aliasA);
  check(`Tras el segundo reinicio, /${state.aliasA} sigue 302`, rA.status === 302);
  check('… al mismo destino de la fase A', location === state.destinoA);

  const { res: cZ, body: bZ } = await createLink('https://entrega.ejemplo.com/final');
  check('Nueva creación -> 201', cZ.status === 201);
  check('La duración de entrega volvió a 60 minutos',
    !!bZ && expiresAtOk(bZ.expiresAt, 60), bZ?.expiresAt);
}

// ---------------------------------------------------------------- main

const phases = { a: phaseA, b: phaseB, c: phaseC };
if (!phases[PHASE]) {
  console.error(`Fase desconocida: ${PHASE}`);
  process.exit(64);
}

try {
  await phases[PHASE]();
} catch (e) {
  failures++;
  console.log(`  [FALLA] excepción no controlada: ${e.stack ?? e}`);
}

console.log(`== Fase ${PHASE.toUpperCase()} terminada: ${checks - failures}/${checks} verificaciones OK ==`);
process.exit(failures === 0 ? 0 : 1);
