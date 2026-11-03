package com.jugarswf;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.widget.Toast;
import org.json.JSONObject;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// La app es la misma página web, servida desde dentro del APK (sin Chrome ni internet).
public class MainActivity extends Activity {
    // Origen https falso: Ruffle carga su .wasm con fetch(), que no funciona con file://
    static final String HOST = "appassets.local";
    static final int PICK_PSP = 2, BACKUP_EXPORT = 3, BACKUP_IMPORT = 4, PICK_FOLDER = 5;
    // Solo se miran archivos con extensión de juego (la web confirma la consola por el contenido)
    static final java.util.regex.Pattern GAME_EXT = java.util.regex.Pattern.compile(
        "(?i)\\.(swf|nes|smc|sfc|gb|gbc|gba|md|gen|smd|sms|gg|chd|pbp|iso|cso|zso|bin|n64|z64|v64|pce|a26|a78|lnx|ngp|ngc|ws|wsc|zip|nds|elf)$");

    // Recorre la carpeta elegida (hasta 6 niveles, 3000 archivos) y pasa a la web la lista de juegos encontrados
    void scanTree(Uri tree) {
        org.json.JSONArray out = new org.json.JSONArray();
        try { walk(tree, android.provider.DocumentsContract.getTreeDocumentId(tree), 0, out); } catch (Exception e) { /* carpeta ya no accesible */ }
        String js = "window.rvScanned&&rvScanned(" + JSONObject.quote(tree.toString()) + "," + out + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    void walk(Uri tree, String docId, int depth, org.json.JSONArray out) throws Exception {
        if (depth > 6 || out.length() >= 3000) return;
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = { android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE };
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue; // ocultos y del sistema
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(tree, id, depth + 1, out);
                else if (GAME_EXT.matcher(name).find())
                    out.put(new JSONObject().put("uri", android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
                        .put("name", name).put("size", c.getLong(3)));
            }
        }
    }
    String pendingBackup;
    WebView web;
    ValueCallback<Uri[]> pick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Inspección remota (chrome://inspect) solo con las opciones de desarrollador del móvil activadas
        WebView.setWebContentsDebuggingEnabled(android.provider.Settings.Global.getInt(getContentResolver(),
            android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        // Android 13+ (y obligatorio con targetSdk 36): el botón atrás llega por OnBackInvokedDispatcher, no por onBackPressed
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Aislamiento: la web solo ve los archivos que la app le sirve (assets y juegos elegidos), nada más del teléfono
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        // Puente para PSP: la web pide elegir/lanzar juegos y el PPSSPP integrado los ejecuta de forma nativa
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void launchDs(String uri, String name) {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, DsActivity.class)
                    .putExtra("uri", uri).putExtra("name", name)));
            }

            // Lee 'len' bytes desde 'offset' de un archivo del teléfono (base64). La web lo usa para detectar la consola
            // por la cabecera y para sacar iconos, sin cargar el archivo entero (los de PSP pesan GB)
            @JavascriptInterface
            public String readBytes(String uri, double offset, int len) {
                try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
                     java.io.FileInputStream in = new java.io.FileInputStream(pfd.getFileDescriptor())) {
                    java.nio.channels.FileChannel ch = in.getChannel();
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(Math.max(0, Math.min(len, 8 << 20)));
                    ch.position((long) offset);
                    while (buf.hasRemaining() && ch.read(buf) > 0) { }
                    return android.util.Base64.encodeToString(buf.array(), 0, buf.position(), android.util.Base64.NO_WRAP);
                } catch (Exception e) {
                    return "";
                }
            }

            // Rotación: la biblioteca gira libremente; los juegos van en horizontal
            @JavascriptInterface
            public void orientation(String mode) {
                boolean game = "landscape".equals(mode);
                runOnUiThread(() -> {
                    setRequestedOrientation(game ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
                    lockRefresh60(MainActivity.this, game);
                });
            }

            // Respaldo: la web pasa sus partidas (JSON); aquí se añaden las nativas y el usuario elige dónde guardar el .zip
            @JavascriptInterface
            public void backupExport(String webJson) {
                pendingBackup = webJson;
                String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "RetroVault-respaldo-" + day + ".zip"), BACKUP_EXPORT);
            }

            @JavascriptInterface
            public void backupImport() {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), BACKUP_IMPORT);
            }

            // Carpetas de juegos: el usuario elige una (permiso permanente) y la app la recorre buscando juegos
            // No molestar durante el juego (opcional). Android exige que el usuario conceda el permiso una vez.
            @JavascriptInterface
            public boolean dndAllowed() { return nm().isNotificationPolicyAccessGranted(); }

            @JavascriptInterface
            public void dndRequest() {
                runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
            }

            @JavascriptInterface
            public void dnd(boolean on) { runOnUiThread(() -> { dndWanted = on; setDnd(on); }); }

            @JavascriptInterface
            public void pickFolder() {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
            }

            @JavascriptInterface
            public void rescan(String treeUri) { new Thread(() -> scanTree(Uri.parse(treeUri)), "scan").start(); }

            @JavascriptInterface
            public void pickNative() { // todos los juegos: permiso permanente; PSP/DS no se copian, el resto sí
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); // OPEN_DOCUMENT: permite permiso permanente
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, PICK_PSP);
            }

            @JavascriptInterface
            public void launchPsp(String uri) {
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                        i.setClassName(MainActivity.this, "org.ppsspp.ppsspp.PpssppActivity");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "No se pudo abrir el juego de PSP", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "RV");

        web.setWebViewClient(new WebViewClient() {
            // Enlaces externos: en el navegador
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* sin app: ignorar */ }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (path.equals("/__content")) { // la web copia a su biblioteca un juego elegido con el selector nativo
                    try {
                        return new WebResourceResponse("application/octet-stream", null,
                            getContentResolver().openInputStream(Uri.parse(u.getQueryParameter("u"))));
                    } catch (Exception e) {
                        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                    }
                }
                try {
                    return new WebResourceResponse(mime(path), null, getAssets().open("www" + path));
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Avisos y confirmaciones con el título "RetroVault" en vez de "La página https://appassets.local dice"
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("RetroVault").setMessage(msg)
                    .setPositiveButton("Aceptar", (d, w) -> r.confirm()).setOnCancelListener(d -> r.confirm()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("RetroVault").setMessage(msg)
                    .setPositiveButton("Aceptar", (d, w) -> r.confirm()).setNegativeButton("Cancelar", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pick != null) pick.onReceiveValue(null);
                pick = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*"); // Android no conoce el tipo de los .swf ni de las ROMs
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                startActivityForResult(i, 1);
                return true;
            }
        });

        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");
    }

    static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    // Juegos elegidos: permiso permanente sobre cada archivo y aviso a la web (nombre + tamaño); ella detecta la consola
    void onPspPicked(Intent data) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null)
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { continue; }
            String name = u.getLastPathSegment();
            long size = 0;
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) { name = c.getString(0); size = c.getLong(1); }
            } catch (Exception e) { /* sin datos: usar el nombre de la uri */ }
            if (size <= 0) try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r")) { size = pfd.getStatSize(); } catch (Exception e) { }
            web.evaluateJavascript("rvNativePicked(" + JSONObject.quote(u.toString()) + "," + JSONObject.quote(name) + "," + size + ")", null);
        }
    }

    // Carpetas nativas que entran en el respaldo: partidas/estados del DS y de PPSSPP (memstick por defecto de la app)
    java.util.Map<String, java.io.File> backupRoots() {
        java.util.Map<String, java.io.File> m = new java.util.LinkedHashMap<>();
        m.put("native/ds-saves/", new java.io.File(getFilesDir(), "ds-saves"));
        m.put("native/ds-states/", new java.io.File(getFilesDir(), "ds-states"));
        java.io.File ext = getExternalFilesDir(null);
        if (ext != null) {
            m.put("native/psp-savedata/", new java.io.File(ext, "PSP/SAVEDATA"));
            m.put("native/psp-states/", new java.io.File(ext, "PSP/PPSSPP_STATE"));
        }
        return m;
    }

    void zipDir(java.util.zip.ZipOutputStream z, java.io.File dir, String prefix) throws java.io.IOException {
        java.io.File[] list = dir.listFiles();
        if (list == null) return;
        byte[] buf = new byte[1 << 16];
        for (java.io.File f : list) {
            if (f.isDirectory()) { zipDir(z, f, prefix + f.getName() + "/"); continue; }
            z.putNextEntry(new java.util.zip.ZipEntry(prefix + f.getName()));
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int n; while ((n = in.read(buf)) > 0) z.write(buf, 0, n); }
            z.closeEntry();
        }
    }

    void writeBackup(Uri out) {
        try (java.io.OutputStream os = getContentResolver().openOutputStream(out);
             java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(os)) {
            z.putNextEntry(new java.util.zip.ZipEntry("web.json"));
            z.write(pendingBackup.getBytes("UTF-8"));
            z.closeEntry();
            for (java.util.Map.Entry<String, java.io.File> r : backupRoots().entrySet()) zipDir(z, r.getValue(), r.getKey());
            Toast.makeText(this, "✅ Respaldo guardado", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar el respaldo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        pendingBackup = null;
    }

    void readBackup(Uri in) {
        String webJson = null;
        java.util.Map<String, java.io.File> roots = backupRoots();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(in))) {
            byte[] buf = new byte[1 << 16];
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                if (e.getName().equals("web.json")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    for (int n; (n = z.read(buf)) > 0; ) bo.write(buf, 0, n);
                    webJson = bo.toString("UTF-8");
                    continue;
                }
                for (java.util.Map.Entry<String, java.io.File> r : roots.entrySet()) {
                    if (!e.getName().startsWith(r.getKey())) continue;
                    java.io.File base = r.getValue().getCanonicalFile();
                    java.io.File dst = new java.io.File(base, e.getName().substring(r.getKey().length())).getCanonicalFile();
                    // Seguridad (zip slip): nunca escribir fuera de la carpeta de partidas correspondiente
                    if (!dst.getPath().startsWith(base.getPath() + java.io.File.separator)) break;
                    dst.getParentFile().mkdirs();
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(dst)) { for (int n; (n = z.read(buf)) > 0; ) fo.write(buf, 0, n); }
                    break;
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo leer el respaldo: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (webJson == null) { Toast.makeText(this, "El archivo no es un respaldo de RetroVault", Toast.LENGTH_LONG).show(); return; }
        web.evaluateJavascript("rvRestore(" + JSONObject.quote(webJson) + ")", null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_PSP) { if (res == RESULT_OK && data != null) onPspPicked(data); return; }
        if (req == BACKUP_EXPORT) { if (res == RESULT_OK && data != null && pendingBackup != null) writeBackup(data.getData()); else pendingBackup = null; return; }
        if (req == BACKUP_IMPORT) { if (res == RESULT_OK && data != null) readBackup(data.getData()); return; }
        if (req == PICK_FOLDER) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { /* sin permiso permanente: solo esta vez */ }
                web.evaluateJavascript("rvFolderPicked(" + JSONObject.quote(tree.toString()) + ")", null);
            }
            return;
        }
        if (pick == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) { // varios archivos elegidos a la vez
                out = new Uri[data.getClipData().getItemCount()];
                for (int k = 0; k < out.length; k++) out[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        pick.onReceiveValue(out);
        pick = null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // Pantallas de 90/120/144 Hz: los emuladores web sincronizan con la pantalla (VSync) y a 120 Hz el juego podría
    // ir al doble de velocidad. Durante el juego se pide a Android un modo de 60 Hz (y se ahorra batería).
    static void lockRefresh60(Activity a, boolean on) {
        android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.preferredDisplayModeId = 0; lp.preferredRefreshRate = 0; // 0 = lo que decida el sistema
        if (on && android.os.Build.VERSION.SDK_INT >= 23) {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes())
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && Math.abs(m.getRefreshRate() - 60f) < 1f) { lp.preferredDisplayModeId = m.getModeId(); break; }
            lp.preferredRefreshRate = 60f;
        }
        a.getWindow().setAttributes(lp);
    }

    // No molestar: se guarda el modo que tenía el usuario y se restaura SIEMPRE al salir del juego o de la app
    android.app.NotificationManager nm() { return (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE); }
    boolean dndWanted = false;
    int prevFilter = -1;
    void setDnd(boolean on) {
        android.app.NotificationManager n = nm();
        if (!n.isNotificationPolicyAccessGranted()) return;
        if (on && prevFilter < 0) {
            prevFilter = n.getCurrentInterruptionFilter();
            n.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY); // alarmas y prioritarios sí pasan
        } else if (!on && prevFilter >= 0) {
            n.setInterruptionFilter(prevFilter);
            prevFilter = -1;
        }
    }

    // Segundo plano (llamada, mensaje, inicio): la web guarda y pausa el juego, y el WebView deja de ejecutar
    // JavaScript/temporizadores -> el emulador no consume CPU ni batería mientras no se ve
    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) { web.evaluateJavascript("window.rvPause&&rvPause()", null); web.onPause(); web.pauseTimers(); }
        setDnd(false); // fuera de la app: notificaciones como siempre
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); web.evaluateJavascript("window.rvResume&&rvResume()", null); }
        if (dndWanted) setDnd(true); // de vuelta en el juego
    }

    @Override
    protected void onDestroy() { setDnd(false); super.onDestroy(); }

    // ---------- Mandos físicos (Bluetooth / USB): cada mando es un jugador, en orden de primer uso ----------
    final java.util.List<Integer> pads = new java.util.ArrayList<>(); // id de dispositivo por jugador
    final float[][] axes = new float[4][8];                            // último valor enviado por jugador/eje

    static boolean isPad(android.view.InputEvent e) {
        int s = e.getSource();
        return (s & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
            || (s & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }
    int playerOf(int device) {
        int p = pads.indexOf(device);
        if (p < 0 && pads.size() < 4) {
            pads.add(device); p = pads.size() - 1;
            Toast.makeText(this, "🎮 Mando " + (p + 1) + " conectado", Toast.LENGTH_SHORT).show();
        }
        return p;
    }
    // Botón de Android -> id libretro (A de Xbox = botón de abajo = "B" de Nintendo, como en RetroArch)
    static int retroId(int key) {
        switch (key) {
            case android.view.KeyEvent.KEYCODE_BUTTON_A: return 0;
            case android.view.KeyEvent.KEYCODE_BUTTON_B: return 8;
            case android.view.KeyEvent.KEYCODE_BUTTON_X: return 1;
            case android.view.KeyEvent.KEYCODE_BUTTON_Y: return 9;
            case android.view.KeyEvent.KEYCODE_BUTTON_L1: return 10;
            case android.view.KeyEvent.KEYCODE_BUTTON_R1: return 11;
            case android.view.KeyEvent.KEYCODE_BUTTON_L2: return 12;
            case android.view.KeyEvent.KEYCODE_BUTTON_R2: return 13;
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBL: return 14;
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR: return 15;
            case android.view.KeyEvent.KEYCODE_BUTTON_START: return 3;
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT: return 2;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: return 4;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            default: return -1;
        }
    }
    void js(String code) { web.evaluateJavascript(code, null); }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int id = retroId(e.getKeyCode());
        if (id >= 0 && isPad(e)) {
            int p = playerOf(e.getDeviceId());
            if (p >= 0 && e.getRepeatCount() == 0 && e.getAction() != android.view.KeyEvent.ACTION_MULTIPLE)
                js("window.rvPad&&rvPad(" + p + "," + id + "," + (e.getAction() == android.view.KeyEvent.ACTION_DOWN) + ")");
            return true; // consumido: que el WebView no lo convierta además en teclas
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!isPad(e) || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        int p = playerOf(e.getDeviceId());
        if (p < 0) return true;
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        float[] now = { e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
            e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
            e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), lt, rt };
        for (int a = 0; a < 8; a++) {
            float v = Math.abs(now[a]) < 0.15f ? 0 : Math.round(now[a] * 20) / 20f; // zona muerta y menos mensajes
            if (v == axes[p][a]) continue;
            float old = axes[p][a]; axes[p][a] = v;
            if (a < 4) js("window.rvAxis&&rvAxis(" + p + "," + a + "," + v + ")");          // sticks
            else if (a < 6) {                                                                   // cruceta tipo "hat"
                int neg = a == 4 ? 6 : 4, pos = a == 4 ? 7 : 5;
                if ((old < -0.5f) != (v < -0.5f)) js("window.rvPad&&rvPad(" + p + "," + neg + "," + (v < -0.5f) + ")");
                if ((old > 0.5f) != (v > 0.5f)) js("window.rvPad&&rvPad(" + p + "," + pos + "," + (v > 0.5f) + ")");
            } else if ((old > 0.5f) != (v > 0.5f))                                            // gatillos analógicos -> L2/R2
                js("window.rvPad&&rvPad(" + p + "," + (a == 6 ? 12 : 13) + "," + (v > 0.5f) + ")");
        }
        return true;
    }

    @Override
    public void onBackPressed() { goBack(); } // Android 12 o anterior

    // En un juego: pulsa la flecha ← de la página (guarda la partida y vuelve). En la biblioteca: salir.
    void goBack() {
        web.evaluateJavascript("(function(){var b=document.getElementById('back');if(b){b.click();return 1}return 0})()",
            r -> { if (!"1".equals(r)) finish(); });
    }
}
