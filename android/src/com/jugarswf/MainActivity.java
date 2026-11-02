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
    static final int PICK_PSP = 2;
    WebView web;
    ValueCallback<Uri[]> pick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_PSP) { if (res == RESULT_OK && data != null) onPspPicked(data); return; }
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

    // Segundo plano (llamada, mensaje, inicio): la web guarda y pausa el juego, y el WebView deja de ejecutar
    // JavaScript/temporizadores -> el emulador no consume CPU ni batería mientras no se ve
    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) { web.evaluateJavascript("window.rvPause&&rvPause()", null); web.onPause(); web.pauseTimers(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); web.evaluateJavascript("window.rvResume&&rvResume()", null); }
    }

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
    public void onBackPressed() {
        // En un juego: pulsa la flecha ← de la página (guarda la partida y vuelve). En la biblioteca: salir.
        web.evaluateJavascript("(function(){var b=document.getElementById('back');if(b){b.click();return 1}return 0})()",
            r -> { if (!"1".equals(r)) finish(); });
    }
}
