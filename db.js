// Biblioteca de RetroVault: los juegos se copian dentro de la app (IndexedDB), así no hay que buscarlos cada vez.
const SYSTEMS = {
  swf:    { name: "Flash",        color: "#e5533d" },
  nes:    { name: "NES",          color: "#c0392b" },
  snes:   { name: "Super NES",    color: "#6c5ce7" },
  gba:    { name: "Game Boy / GBA", color: "#2e86de" }, // mGBA corre los tres (GB, GBC y GBA)
  segaMD: { name: "Sega Genesis", color: "#222f3e" },
  segaMS: { name: "Master System",color: "#10ac84" },
  segaGG: { name: "Game Gear",    color: "#576574" },
  psp:    { name: "PSP",          color: "#1e3799" },
  psx:    { name: "PlayStation",  color: "#535c68" },
  n64:    { name: "Nintendo 64",  color: "#009432" },
  arcade: { name: "Arcade",       color: "#b33939" },
  pce:    { name: "PC Engine",    color: "#e15f41" },
  atari2600: { name: "Atari 2600", color: "#8e5b2a" },
  atari7800: { name: "Atari 7800", color: "#6d4c41" },
  lynx:   { name: "Atari Lynx",   color: "#3d3d3d" },
  ngp:    { name: "Neo Geo Pocket", color: "#0a3d62" },
  ws:     { name: "WonderSwan",   color: "#596275" },
  nds:    { name: "Nintendo DS",  color: "#7f8c8d" },
};
// Consolas con emulador nativo (solo app Android); la extensión decide cuál
const NATIVE = { iso: "psp", cso: "psp", chd: "psp", pbp: "psp", elf: "psp", nds: "nds" };
const EXT = { swf: "swf", nes: "nes", smc: "snes", sfc: "snes", gb: "gba", gbc: "gba", gba: "gba",
  md: "segaMD", gen: "segaMD", smd: "segaMD", sms: "segaMS", gg: "segaGG",
  // .iso aquí es PS1; en el botón PSP/DS (NATIVE) es PSP
  chd: "psx", pbp: "psx", iso: "psx", n64: "n64", z64: "n64", v64: "n64", pce: "pce", a26: "atari2600", a78: "atari7800",
  lnx: "lynx", ngp: "ngp", ngc: "ngp", ws: "ws", wsc: "ws" };
// Extensiones que pueden ser de varias consolas: se pregunta al añadir (EmulatorJS descomprime .zip/.7z solo)
const AMBIGUOUS = { zip: ["arcade", "psx", "nes", "snes", "gba", "segaMD", "n64"], "7z": ["psx", "nes", "snes", "gba", "segaMD", "n64"],
  bin: ["psx", "segaMD", "atari2600"] };
const isBackup = fileName => /^RetroVault-respaldo.*\.zip$/i.test(fileName); // .zip de respaldo de RetroVault
const systemOf = fileName => EXT[fileName.split(".").pop().toLowerCase()];
const choicesOf = fileName => AMBIGUOUS[fileName.split(".").pop().toLowerCase()];

// --- Detección automática de consola por el CONTENIDO del archivo (firmas de cabecera), no por la extensión ---
// read(offset, length) -> Promise<Uint8Array>. Devuelve la clave de SYSTEMS o null si no se puede saber.
const NATIVE_SYSTEMS = new Set(["psp", "nds"]); // en la app Android corren nativos y no se copian a la biblioteca
async function detectSystem(name, size, read) {
  const ext = name.split(".").pop().toLowerCase();
  const txt = (b, o, n) => String.fromCharCode(...b.subarray(o, o + n));
  const le32 = (b, o) => (b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24) >>> 0;
  const be32 = (b, o) => (b[o] << 24 | b[o + 1] << 16 | b[o + 2] << 8 | b[o + 3]) >>> 0;
  const h = await read(0, Math.min(size, 0x200));

  if (/^[FCZ]WS/.test(txt(h, 0, 3))) return "swf";
  if (txt(h, 0, 4) === "NES\x1a") return "nes";
  if (txt(h, 0, 4) === "CISO" || txt(h, 0, 4) === "ZISO") return "psp";              // .cso / .zso
  if (txt(h, 1, 9) === "ATARI7800") return "atari7800";
  if (txt(h, 0, 4) === "LYNX") return "lynx";
  if (/(LICENSED|COPYRIGHT) BY SNK/.test(txt(h, 0, 28))) return "ngp";
  if ([0x80371240, 0x37804012, 0x40123780].includes(be32(h, 0))) return "n64";          // .z64 / .v64 / .n64
  if (txt(h, 0, 4) === "\0PBP") {                                                       // EBOOT: PSP o PS1 convertido
    const psar = await read(le32(h, 0x24), 8);
    return /^PSISOIMG|^PSTITLEI/.test(txt(psar, 0, 8)) ? "psx" : "psp";
  }
  if (txt(h, 0, 8) === "MComprHD") {                                                    // CHD: CD = PS1, DVD = PSP
    const meta = await read(Number(be32(h, 0x30)) * 2 ** 32 + be32(h, 0x34), 4);
    return txt(meta, 0, 4) === "DVD " ? "psp" : "psx";
  }
  if (txt(h, 0, 4) === "PK\x03\x04") return detectZip(size, read);
  if (h.length >= 0x160 && h[0x15C] === 0x56 && h[0x15D] === 0xCF) return "nds";       // CRC del logo de Nintendo DS
  if (h[0xB2] === 0x96 && be32(h, 4) === 0x24FFAE51) return "gba";                     // logo de Nintendo en GBA
  if (be32(h, 0x104) === 0xCEED6666) return "gba";                                     // Game Boy / Color (los corre mGBA)
  if (/SEGA/.test(txt(h, 0x100, 5))) return "segaMD";
  if (size > 0x9400) {                                                                  // imagen de CD/DVD (ISO 9660)
    for (const off of [0x8000, 0x9310, 0x9318]) {                                       // ISO normal y .bin de 2352 bytes/sector
      const pvd = await read(off, 0x30);
      if (txt(pvd, 1, 5) === "CD001") {
        const id = txt(pvd, 8, 32);
        if (/PSP GAME/.test(id)) return "psp";
        if (/PLAYSTATION/.test(id)) return "psx";
      }
    }
    const sync = await read(0, 12);                                                     // .bin de CD sin cabecera reconocible
    if (sync[0] === 0 && sync.subarray(1, 11).every(v => v === 0xFF) && sync[11] === 0) return "psx";
  }
  for (const off of [0x7FF0, 0x3FF0, 0x1FF0]) {                                         // Master System / Game Gear
    if (size < off + 16) continue;
    const t = await read(off, 16);
    if (txt(t, 0, 8) === "TMR SEGA") { const region = t[15] >> 4; return region >= 5 && region <= 7 ? "segaGG" : "segaMS"; }
  }
  for (const base of [0x7FC0, 0xFFC0]) for (const copier of [0, 0x200]) {              // SNES: checksum + complemento = 0xFFFF
    if (size < base + copier + 0x20) continue;
    const t = await read(base + copier, 0x20);
    const comp = t[0x1C] | t[0x1D] << 8, sum = t[0x1E] | t[0x1F] << 8;
    if ((comp ^ sum) === 0xFFFF && sum !== 0 && sum !== 0xFFFF) return "snes";
  }
  // Sin firma: PC Engine, WonderSwan y Atari 2600 no tienen cabecera; se decide por la extensión
  if (ext === "pce") return "pce";
  if (ext === "ws" || ext === "wsc") return "ws";
  if ((ext === "a26" || ext === "bin") && size <= 64 * 1024) return "atari2600";
  return EXT[ext] && !AMBIGUOUS[ext] ? EXT[ext] : null;
}
// .zip: si dentro hay una ROM de consola conocida es esa consola (EmulatorJS descomprime solo); si no, es Arcade (FBNeo)
async function detectZip(size, read) {
  const tailLen = Math.min(size, 65557), tail = await read(size - tailLen, tailLen);
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail[i] === 0x50 && tail[i + 1] === 0x4B && tail[i + 2] === 5 && tail[i + 3] === 6) {
      const n = tail[i + 10] | tail[i + 11] << 8;
      const cdSize = (tail[i + 12] | tail[i + 13] << 8 | tail[i + 14] << 16 | tail[i + 15] << 24) >>> 0;
      const cdOff = (tail[i + 16] | tail[i + 17] << 8 | tail[i + 18] << 16 | tail[i + 19] << 24) >>> 0;
      const cd = await read(cdOff, cdSize);
      for (let p = 0, k = 0; k < n && p + 46 <= cd.length; k++) {
        const nameLen = cd[p + 28] | cd[p + 29] << 8, extra = cd[p + 30] | cd[p + 31] << 8, comment = cd[p + 32] | cd[p + 33] << 8;
        const inner = String.fromCharCode(...cd.subarray(p + 46, p + 46 + nameLen));
        if (inner === "web.json") return null; // respaldo de RetroVault, no un juego
        const sys = EXT[inner.split(".").pop().toLowerCase()];
        if (sys && sys !== "swf") return sys;
        p += 46 + nameLen + extra + comment;
      }
      return "arcade";
    }
  }
  return "arcade";
}

const DB = new Promise((ok, err) => {
  const r = indexedDB.open("retrovault", 2); // v2: almacén "states" (autoguardado de partida al salir)
  r.onupgradeneeded = () => {
    const db = r.result;
    if (!db.objectStoreNames.contains("games")) db.createObjectStore("games", { keyPath: "id", autoIncrement: true });
    if (!db.objectStoreNames.contains("states")) db.createObjectStore("states"); // clave: id del juego
  };
  r.onsuccess = () => ok(r.result);
  r.onerror = () => err(r.error);
});
async function tx(mode, fn, store = "games") {
  const db = await DB;
  return new Promise((ok, err) => {
    const t = db.transaction(store, mode);
    const req = fn(t.objectStore(store));
    t.oncomplete = () => ok(req.result);
    t.onerror = () => err(t.error);
  });
}
const games = {
  all: () => tx("readonly", s => s.getAll()),
  get: id => tx("readonly", s => s.get(id)),
  // fileName es el nombre original: Ruffle y EmulatorJS guardan la partida bajo ese nombre
  add: (file, system = systemOf(file.name)) => tx("readwrite", s => s.add({ name: file.name.replace(/\.[^.]+$/, ""), fileName: file.name,
    system, file, added: Date.now() })),
  // PSP / DS (nativos): solo se guarda la referencia (uri con permiso permanente); el archivo se queda en el teléfono
  addNative: (uri, fileName, system) => tx("readwrite", s => s.add({ name: fileName.replace(/\.[^.]+$/, ""), fileName,
    system, uri, added: Date.now() })),
  del: id => Promise.all([tx("readwrite", s => s.delete(id)), states.del(id)]),
  put: g => tx("readwrite", s => s.put(g)),
};
// Estado completo de la partida (save state) guardado al salir: sobrevive a que Android cierra la app por falta de RAM
const states = {
  get: id => tx("readonly", s => s.get(id), "states"),
  put: (id, data) => tx("readwrite", s => s.put({ data, at: Date.now() }, id), "states"),
  del: id => tx("readwrite", s => s.delete(id), "states"),
};

// --- Respaldo de partidas (sin ROMs) ---
// Partidas de consolas: EmulatorJS las guarda en la base IDBFS de Emscripten "/data/saves" (almacén FILE_DATA, versión 21).
// Flash (Ruffle) y ajustes: localStorage. Autoguardados: almacén "states" (se asocian por nombre de archivo, no por id).
const SAVES_DB = ["/data/saves", "FILE_DATA", 21];
const b64of = async u8 => (await new Promise(r => { const f = new FileReader(); f.onload = () => r(f.result); f.readAsDataURL(new Blob([u8])); })).split(",")[1];
const u8of = b64 => Uint8Array.from(atob(b64), c => c.charCodeAt(0));
function idbOpen(name, store, version) {
  return new Promise((ok, err) => {
    const r = version ? indexedDB.open(name, version) : indexedDB.open(name);
    r.onupgradeneeded = () => { // igual que crea Emscripten su IDBFS
      if (!r.result.objectStoreNames.contains(store)) r.result.createObjectStore(store).createIndex("timestamp", "timestamp", { unique: false });
    };
    r.onsuccess = () => ok(r.result); r.onerror = () => err(r.error);
  });
}
async function idbAll(name, store, version) {
  const db = await idbOpen(name, store, version);
  if (!db.objectStoreNames.contains(store)) { db.close(); return []; }
  return new Promise((ok, err) => {
    const out = [], c = db.transaction(store).objectStore(store).openCursor();
    c.onsuccess = () => { const cur = c.result; if (cur) { out.push({ k: cur.key, v: cur.value }); cur.continue(); } else { db.close(); ok(out); } };
    c.onerror = () => err(c.error);
  });
}
async function exportBackup() {
  const byId = Object.fromEntries((await games.all()).map(g => [g.id, g]));
  const saves = await Promise.all((await idbAll(...SAVES_DB)).map(async ({ k, v }) =>
    ({ k, t: +v.timestamp, m: v.mode, c: v.contents ? await b64of(v.contents) : null })));
  const st = await Promise.all((await idbAll("retrovault", "states")).map(async ({ k, v }) =>
    byId[k] && { fileName: byId[k].fileName, system: byId[k].system, at: v.at, data: await b64of(v.data) }));
  return JSON.stringify({ app: "RetroVault", v: 1, at: Date.now(), localStorage: { ...localStorage }, saves, states: st.filter(Boolean) });
}
async function importBackup(json) {
  const b = JSON.parse(json);
  if (b.app !== "RetroVault" || !Array.isArray(b.saves)) throw new Error("El archivo no es un respaldo de RetroVault");
  for (const [k, v] of Object.entries(b.localStorage || {})) localStorage.setItem(k, v);
  const db = await idbOpen(...SAVES_DB);
  await new Promise((ok, err) => {
    const t = db.transaction(SAVES_DB[1], "readwrite"), s = t.objectStore(SAVES_DB[1]);
    for (const x of b.saves) s.put({ timestamp: new Date(x.t), mode: x.m, ...(x.c != null && { contents: u8of(x.c) }) }, x.k);
    t.oncomplete = () => { db.close(); ok(); }; t.onerror = () => err(t.error);
  });
  const lib = await games.all();
  let matched = 0;
  for (const s of b.states || []) {
    const g = lib.find(x => x.fileName === s.fileName && x.system === s.system);
    if (g) { await states.put(g.id, u8of(s.data)); matched++; }
  }
  return { saves: b.saves.filter(x => x.c != null).length, states: matched, statesTotal: (b.states || []).length };
}

// --- Carátulas automáticas (libretro-thumbnails, las de RetroArch). Se guardan en la app: luego se ven sin internet ---
const COVER_REPO = { nes: "Nintendo_-_Nintendo_Entertainment_System", snes: "Nintendo_-_Super_Nintendo_Entertainment_System",
  gb: "Nintendo_-_Game_Boy", gbc: "Nintendo_-_Game_Boy_Color", gba: "Nintendo_-_Game_Boy_Advance",
  segaMD: "Sega_-_Mega_Drive_-_Genesis", segaMS: "Sega_-_Master_System_-_Mark_III", segaGG: "Sega_-_Game_Gear",
  psp: "Sony_-_PlayStation_Portable", nds: "Nintendo_-_Nintendo_DS", psx: "Sony_-_PlayStation", n64: "Nintendo_-_Nintendo_64",
  pce: "NEC_-_PC_Engine_-_TurboGrafx_16", atari2600: "Atari_-_2600", atari7800: "Atari_-_7800", lynx: "Atari_-_Lynx",
  ngp: "SNK_-_Neo_Geo_Pocket", ngc: "SNK_-_Neo_Geo_Pocket_Color", ws: "Bandai_-_WonderSwan", wsc: "Bandai_-_WonderSwan_Color" };
// Arcade no tiene: las ROMs se llaman por código (sf2.zip) y las carátulas por título completo
// --- Número de serie de discos PS1/PSP -> nombre oficial (libretro-database), para la carátula ---
const SERIAL_DB = { psx: "metadat/redump/Sony - PlayStation.dat", psp: "metadat/serial/Sony - PlayStation Portable.dat" };
const serialMaps = {}; // una descarga por sesión y consola
// "SLUS_005.94" / "ULUS10041" / "SLUS-00594" -> "SLUS-00594" (formato de la base de datos)
const normSerial = s => { const m = s && s.match(/([A-Z]{4})[_-]?(\d{3})\.?(\d{2})/); return m ? `${m[1]}-${m[2]}${m[3]}` : null; };
// Lee los primeros bytes de un archivo de la raíz de una imagen ISO 9660 (ISO normal o .bin de 2352 bytes/sector)
async function isoRootFile(read, name, len) {
  for (const pvdOff of [0x8000, 0x9310, 0x9318]) {
    const pvd = await read(pvdOff, 190);
    if (String.fromCharCode(...pvd.subarray(1, 6)) !== "CD001") continue;
    const ss = pvdOff === 0x8000 ? 2048 : 2352, at = lba => lba * ss + pvdOff - 16 * ss;
    const dir = await read(at(le32at(pvd, 158)), 2048);
    for (let p = 0; p + 33 < dir.length && dir[p]; p += dir[p])
      if (String.fromCharCode(...dir.subarray(p + 33, p + 33 + dir[p + 32])).toUpperCase().startsWith(name)) return read(at(le32at(dir, p + 2)), len);
    return null;
  }
  return null;
}
async function discSerial(g) {
  const read = readerFor(g);
  if (!read) return null;
  const h = await read(0, 0x10), txt = b => b ? String.fromCharCode(...b) : "";
  if (txt(h.subarray(0, 4)) === "\0PBP") return normSerial(txt(await read(le32at(h, 0x08), 0x800)).match(/[A-Z]{4}\d{5}/)?.[0]); // DISC_ID del PARAM.SFO
  if (g.system === "psp") return normSerial(txt(await isoRootFile(read, "UMD_DATA.BIN", 16)));              // "ULUS-10041|..."
  return normSerial(txt(await isoRootFile(read, "SYSTEM.CNF", 128)).match(/BOOT\s*=\s*cdrom:\\?([^;\s]+)/i)?.[1]); // PS1
}
async function serialName(g) {
  const serial = await discSerial(g).catch(() => null);
  if (!serial) return null;
  serialMaps[g.system] ??= fetch("https://raw.githubusercontent.com/libretro/libretro-database/master/" + encodeURI(SERIAL_DB[g.system]))
    .then(r => { if (!r.ok) throw new Error("base de datos " + r.status); return r.text(); })
    .then(text => {
      const map = new Map();
      for (const [, body] of text.matchAll(/game \(([\s\S]*?)\n\)/g)) {
        const name = body.match(/(?:name|comment) "([^"]*)"/)?.[1], ser = body.match(/serial "([^"]*)"/)?.[1];
        if (name && ser) for (const s of ser.split(/,\s*/)) if (!map.has(s)) map.set(s, name);
      }
      return map;
    })
    .catch(e => { delete serialMaps[g.system]; throw e; }); // sin red: reintentar la próxima vez
  return (await serialMaps[g.system]).get(serial) || null;
}

async function findCover(g) {
  const ext = g.fileName.split(".").pop().toLowerCase();
  // gb/gbc/gba, ngp/ngc y ws/wsc comparten emulador pero no carátulas: se decide por la extensión
  const key = ["gba", "ngp", "ws"].includes(g.system) && COVER_REPO[ext] ? ext : g.system;
  // Juegos GB con mejoras de color (.gbc) están archivados en Game Boy, y viceversa: probar ambos (igual NGP y WS)
  const pairs = { gb: ["gb", "gbc"], gbc: ["gbc", "gb"], ngp: ["ngp", "ngc"], ngc: ["ngc", "ngp"], ws: ["ws", "wsc"], wsc: ["wsc", "ws"] };
  const repos = pairs[key]?.map(k => COVER_REPO[k]) || [COVER_REPO[key]];
  if (!repos[0]) return null; // Flash: no hay archivo de carátulas
  // Los archivos se llaman como No-Intro, p.ej. "Super Mario World (USA)". Probamos el nombre tal cual y con regiones típicas.
  const base = g.name.replace(/\s*\[[^\]]*\]/g, "").trim();         // quita etiquetas tipo [!]
  const bare = base.replace(/\s*\([^)]*\)/g, "").trim();             // sin "(USA)" etc.
  // PS1/PSP: el número de serie del disco da el nombre oficial aunque el archivo esté renombrado; se prueba primero
  const official = SERIAL_DB[g.system] ? await serialName(g) : null;
  const names = [...new Set([official, base, ...["USA", "USA, Europe", "World", "Europe", "Japan", "USA, Canada"].map(r => `${bare} (${r})`), bare].filter(Boolean))];
  for (const repo of repos) for (const n of names) {
    const file = n.replace(/[&*/:`<>?\\|"]/g, "_"); // libretro reemplaza estos caracteres por _
    // fetch solo lanza error si no hay red; un 404 (no existe esa carátula) es r.ok = false
    const r = await fetch(`https://raw.githubusercontent.com/libretro-thumbnails/${repo}/master/Named_Boxarts/${encodeURIComponent(file)}.png`);
    if (r.ok) return await r.blob();
  }
  return null;
}
// --- Fichas de juego (desarrollador, editor, año, género) de libretro-database, buscadas por CRC de la ROM ---
const CRC_TABLE = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function crc32(u8) { let c = 0xFFFFFFFF; for (let i = 0; i < u8.length; i++) c = CRC_TABLE[(c ^ u8[i]) & 255] ^ (c >>> 8); return ((c ^ 0xFFFFFFFF) >>> 0).toString(16).toUpperCase().padStart(8, "0"); }
// Busca en un .dat de libretro: bloques  game ( comment "Nombre" <campo> "valor" rom ( crc XXXXXXXX ) )
function datLookup(text, field, crc, names) {
  const re = new RegExp(`comment "([^"]*)"\\s*${field} "([^"]*)"\\s*rom \\(([^)]*)\\)`, "g");
  let byName = null;
  for (const m of text.matchAll(re)) {
    if (crc && new RegExp(`crc ${crc}\\b`, "i").test(m[3])) return m[2];
    if (!byName && names.includes(m[1].toLowerCase())) byName = m[2];
  }
  return byName;
}
async function gameInfo(g) {
  if (g.info) return g.info;
  const ext = g.fileName.split(".").pop().toLowerCase();
  const key = ["gba", "ngp", "ws"].includes(g.system) && COVER_REPO[ext] ? ext : g.system;
  const repo = COVER_REPO[key];
  if (!repo) return null; // Flash / Arcade: sin base de datos
  let crc = null;
  if (g.file && g.file.size <= 64 << 20) { // las ROMs de cartucho: CRC sin cabeceras añadidas (como No-Intro)
    let data = new Uint8Array(await g.file.arrayBuffer());
    if (g.system === "nes" && data[0] === 0x4E && data[1] === 0x45) data = data.subarray(16);   // cabecera iNES
    if (g.system === "snes" && data.length % 1024 === 512) data = data.subarray(512);            // cabecera de copiadora
    crc = crc32(data);
  }
  const bare = g.name.replace(/\s*[([][^)\]]*[)\]]/g, "").trim();
  const names = [g.name, ...["USA", "USA, Europe", "World", "Europe", "Japan"].map(r => `${bare} (${r})`)].map(n => n.toLowerCase());
  const sys = encodeURIComponent(repo.replace(/_/g, " "));
  const info = {};
  await Promise.all([["developer", "developer"], ["publisher", "publisher"], ["releaseyear", "year"], ["genre", "genre"]].map(async ([f, k]) => {
    const r = await fetch(`https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/${f}/${sys}.dat`);
    if (r.ok) info[k] = datLookup(await r.text(), f, crc, names);
  }));
  if (!Object.values(info).some(Boolean)) return null;
  const cur = await games.get(g.id); // guardar la ficha: luego se ve sin internet
  if (cur) await games.put({ ...cur, info });
  return info;
}

// --- Icono incluido en el propio juego (si no hay carátula oficial; funciona sin internet) ---
// Lector de bytes de un juego: archivo guardado (web) o archivo del teléfono vía la app Android (PSP/DS)
function readerFor(g) {
  if (g.file) return async (o, n) => new Uint8Array(await g.file.slice(o, o + n).arrayBuffer());
  if (g.uri && globalThis.RV) return async (o, n) => Uint8Array.from(atob(RV.readBytes(g.uri, o, n)), c => c.charCodeAt(0));
  return null;
}
const le32at = (b, o) => (b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24) >>> 0;
// PSP EBOOT.PBP: la cabecera apunta a ICON0.PNG (entre los offsets 0x0C y 0x10)
async function pbpIcon(read) {
  const h = await read(0, 0x28);
  if (String.fromCharCode(...h.subarray(0, 4)) !== "\0PBP") return null;
  const start = le32at(h, 0x0C), end = le32at(h, 0x10);
  if (end <= start || end - start > 4 << 20) return null;
  const png = await read(start, end - start);
  return png[1] === 0x50 && png[2] === 0x4E && png[3] === 0x47 ? png : null; // firma "PNG"
}
// Nintendo DS: icono 32x32 de 16 colores en el banner (offset en 0x68): 4x4 tiles de 8x8, 4 bits por píxel, paleta BGR555
async function ndsIconPixels(read) {
  const off = le32at(await read(0x68, 4), 0);
  if (!off) return null;
  const b = await read(off + 0x20, 0x220), px = new Uint8ClampedArray(32 * 32 * 4);
  for (let i = 0; i < 1024; i++) {
    const tile = i >> 6, inTile = i & 63, ty = tile >> 2, tx = tile & 3, y = ty * 8 + (inTile >> 3), x = tx * 8 + (inTile & 7);
    const idx = (b[i >> 1] >> ((i & 1) * 4)) & 15;
    if (!idx) continue; // color 0 = transparente
    const c = b[0x200 + idx * 2] | b[0x201 + idx * 2] << 8, p = (y * 32 + x) * 4;
    px[p] = (c & 31) * 255 / 31; px[p + 1] = (c >> 5 & 31) * 255 / 31; px[p + 2] = (c >> 10 & 31) * 255 / 31; px[p + 3] = 255;
  }
  return px;
}
async function gameIcon(g) {
  const read = readerFor(g);
  if (!read) return null;
  try {
    if (g.system === "psp") { const png = await pbpIcon(read); return png && new Blob([png], { type: "image/png" }); }
    if (g.system === "nds") {
      const px = await ndsIconPixels(read);
      if (!px) return null;
      const small = new OffscreenCanvas(32, 32); small.getContext("2d").putImageData(new ImageData(px, 32, 32), 0, 0);
      const big = new OffscreenCanvas(256, 256), ctx = big.getContext("2d");
      ctx.imageSmoothingEnabled = false; ctx.drawImage(small, 0, 0, 256, 256); // píxeles nítidos al ampliar
      return await big.convertToBlob({ type: "image/png" });
    }
  } catch { /* archivo ilegible: sin icono */ }
  return null;
}

// Busca la carátula una sola vez por juego (coverTried) para no repetir descargas fallidas
async function ensureCover(g) {
  // coverTried 2 = buscada ya con número de serie; los PS1/PSP buscados antes (true) se reintentan una vez
  if (g.cover || g.coverTried === 2 || (g.coverTried && !SERIAL_DB[g.system])) return false;
  let cover = null;
  if (navigator.onLine) try { cover = await findCover(g); } catch { return false; } // sin red a mitad: no marcar, se reintenta
  if (!cover) cover = await gameIcon(g); // sin carátula oficial: el icono del propio juego (DS/PSP)
  if (!cover && !navigator.onLine) return false; // offline y sin icono: reintentar cuando haya internet
  const cur = await games.get(g.id); // releer: pudo borrarse o recibir carátula manual mientras buscábamos
  if (!cur || cur.cover) return false;
  await games.put({ ...cur, cover, coverTried: 2 });
  return !!cover;
}
