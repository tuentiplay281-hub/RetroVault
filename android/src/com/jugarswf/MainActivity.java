package com.jugarswf;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

        // Puente para PSP: la web pide elegir/lanzar juegos y el PPSSPP integrado los ejecuta de forma nativa
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void launchDs(String uri, String name) {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, DsActivity.class)
                    .putExtra("uri", uri).putExtra("name", name)));
            }

            @JavascriptInterface
            public void pickNative() { // PSP y DS: archivos grandes que no se copian a la biblioteca web
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

    // Juegos de PSP: no se copian (pesan GB); se guarda permiso permanente sobre el archivo y se avisa a la web
    void onPspPicked(Intent data) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null)
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { continue; }
            String name = u.getLastPathSegment();
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception e) { /* sin nombre: usar el de la uri */ }
            web.evaluateJavascript("rvNativePicked(" + JSONObject.quote(u.toString()) + "," + JSONObject.quote(name) + ")", null);
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

    @Override
    public void onBackPressed() {
        // En un juego: pulsa la flecha ← de la página (guarda la partida y vuelve). En la biblioteca: salir.
        web.evaluateJavascript("(function(){var b=document.getElementById('back');if(b){b.click();return 1}return 0})()",
            r -> { if (!"1".equals(r)) finish(); });
    }
}
