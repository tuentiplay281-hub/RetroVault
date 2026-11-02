// Biblioteca de RetroVault: los juegos se copian dentro de la app (IndexedDB), así no hay que buscarlos cada vez.
const SYSTEMS = {
  swf:    { name: "Flash",        color: "#e5533d" },
  nes:    { name: "NES",          color: "#c0392b" },
  snes:   { name: "Super NES",    color: "#6c5ce7" },
  gba:    { name: "Game Boy",     color: "#2e86de" },
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
  chd: "psx", pbp: "psx", n64: "n64", z64: "n64", v64: "n64", pce: "pce", a26: "atari2600", a78: "atari7800",
  lnx: "lynx", ngp: "ngp", ngc: "ngp", ws: "ws", wsc: "ws" };
// Extensiones que pueden ser de varias consolas: se pregunta al añadir (EmulatorJS descomprime .zip/.7z solo)
const AMBIGUOUS = { zip: ["arcade", "psx", "nes", "snes", "gba", "segaMD", "n64"], "7z": ["psx", "nes", "snes", "gba", "segaMD", "n64"],
  bin: ["psx", "segaMD", "atari2600"] };
const systemOf = fileName => EXT[fileName.split(".").pop().toLowerCase()];
const choicesOf = fileName => AMBIGUOUS[fileName.split(".").pop().toLowerCase()];

const DB = new Promise((ok, err) => {
  const r = indexedDB.open("retrovault", 1);
  r.onupgradeneeded = () => r.result.createObjectStore("games", { keyPath: "id", autoIncrement: true });
  r.onsuccess = () => ok(r.result);
  r.onerror = () => err(r.error);
});
async function tx(mode, fn) {
  const db = await DB;
  return new Promise((ok, err) => {
    const t = db.transaction("games", mode);
    const req = fn(t.objectStore("games"));
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
  del: id => tx("readwrite", s => s.delete(id)),
  put: g => tx("readwrite", s => s.put(g)),
};

// --- Carátulas automáticas (libretro-thumbnails, las de RetroArch). Se guardan en la app: luego se ven sin internet ---
const COVER_REPO = { nes: "Nintendo_-_Nintendo_Entertainment_System", snes: "Nintendo_-_Super_Nintendo_Entertainment_System",
  gb: "Nintendo_-_Game_Boy", gbc: "Nintendo_-_Game_Boy_Color", gba: "Nintendo_-_Game_Boy_Advance",
  segaMD: "Sega_-_Mega_Drive_-_Genesis", segaMS: "Sega_-_Master_System_-_Mark_III", segaGG: "Sega_-_Game_Gear",
  psp: "Sony_-_PlayStation_Portable", nds: "Nintendo_-_Nintendo_DS", psx: "Sony_-_PlayStation", n64: "Nintendo_-_Nintendo_64",
  pce: "NEC_-_PC_Engine_-_TurboGrafx_16", atari2600: "Atari_-_2600", atari7800: "Atari_-_7800", lynx: "Atari_-_Lynx",
  ngp: "SNK_-_Neo_Geo_Pocket", ngc: "SNK_-_Neo_Geo_Pocket_Color", ws: "Bandai_-_WonderSwan", wsc: "Bandai_-_WonderSwan_Color" };
// Arcade no tiene: las ROMs se llaman por código (sf2.zip) y las carátulas por título completo
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
  const names = [...new Set([base, ...["USA", "USA, Europe", "World", "Europe", "Japan", "USA, Canada"].map(r => `${bare} (${r})`), bare])];
  for (const repo of repos) for (const n of names) {
    const file = n.replace(/[&*/:`<>?\\|"]/g, "_"); // libretro reemplaza estos caracteres por _
    // fetch solo lanza error si no hay red; un 404 (no existe esa carátula) es r.ok = false
    const r = await fetch(`https://raw.githubusercontent.com/libretro-thumbnails/${repo}/master/Named_Boxarts/${encodeURIComponent(file)}.png`);
    if (r.ok) return await r.blob();
  }
  return null;
}
// Busca la carátula una sola vez por juego (coverTried) para no repetir descargas fallidas
async function ensureCover(g) {
  if (g.cover || g.coverTried || !navigator.onLine) return false;
  let cover;
  try { cover = await findCover(g); } catch { return false; } // sin red a mitad: no marcar, se reintenta
  const cur = await games.get(g.id); // releer: pudo borrarse o recibir carátula manual mientras buscábamos
  if (!cur || cur.cover) return false;
  await games.put({ ...cur, cover, coverTried: true });
  return !!cover;
}
