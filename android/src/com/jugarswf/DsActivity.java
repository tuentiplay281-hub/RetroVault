package com.jugarswf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

// Nintendo DS nativo: el núcleo melonDS DS (libretro) corre en un hilo propio; ver native/rvlibretro.c
public class DsActivity extends Activity {
    static { System.loadLibrary("rvlibretro"); }
    static native double nativeStart(String corePath, String romPath, String systemDir, String saveDir);
    static native int nativeRunFrame(short[] audioOut);
    static native void nativeSetSurface(Object surface);
    static native void nativeInput(int buttons, int x, int y, int down);
    static native void nativeSave();
    static native void nativeStop();
    static native boolean nativeState(String path, boolean save);
    volatile boolean loadState = false;
    File stateFile; // autoguardado del estado completo (por si Android cierra la app sin RAM)

    // Bits RETRO_DEVICE_ID_JOYPAD_*
    static final int B = 0, Y = 1, SELECT = 2, START = 3, UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7, A = 8, X = 9, L = 10, R = 11;

    volatile boolean running = true, paused = false, saveNow = false;
    volatile int buttons, ptrX, ptrY, ptrDown;
    Thread emu;
    TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String uri = getIntent().getStringExtra("uri"), name = getIntent().getStringExtra("name");
        if (uri == null || name == null) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout screenBox = new FrameLayout(this);
        SurfaceView screen = new SurfaceView(this) {
            // Mantener la proporción del DS (256x384: dos pantallas, una encima de otra)
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w), height = Math.min(width * 3 / 2, MeasureSpec.getSize(h));
                setMeasuredDimension(height * 2 / 3, height);
            }
        };
        screen.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { nativeSetSurface(h.getSurface()); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { nativeSetSurface(h.getSurface()); }
            public void surfaceDestroyed(SurfaceHolder h) { nativeSetSurface(null); }
        });
        // Pantalla táctil: coordenadas de libretro van de -0x7fff a 0x7fff sobre toda la imagen
        screen.setOnTouchListener((v, e) -> {
            int act = e.getActionMasked();
            ptrDown = (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) ? 0 : 1;
            ptrX = (int) (Math.max(0, Math.min(1, e.getX() / v.getWidth())) * 0xFFFE) - 0x7FFF;
            ptrY = (int) (Math.max(0, Math.min(1, e.getY() / v.getHeight())) * 0xFFFE) - 0x7FFF;
            pushInput();
            return true;
        });
        screenBox.addView(screen, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(18); status.setText("Cargando " + name + "…");
        screenBox.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(screenBox, new LinearLayout.LayoutParams(-1, 0, 2.2f));
        pad = new Pad(this);
        pad.setAlpha(padAlpha());
        FrameLayout padBox = new FrameLayout(this);
        padBox.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        // ✎: tamaño y opacidad del mando (se guardan)
        android.widget.Button edit = new android.widget.Button(this);
        edit.setText("✎"); edit.setContentDescription("Editar tamaño y opacidad de los controles");
        edit.setOnClickListener(v -> editPad());
        padBox.addView(edit, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.addView(padBox, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        emu = new Thread(() -> run(Uri.parse(uri), name), "ds-emu");
        emu.start();
    }

    // Botones del mando físico (mismos bits libretro que el mando táctil); se suman a los táctiles
    volatile int padMask, hatMask;
    Pad pad; // mando táctil: se atenúa al usar un mando físico y vuelve al tocarlo
    void padUsed() { if (pad != null && pad.getAlpha() > 0.2f) pad.setAlpha(0.12f); }
    android.content.SharedPreferences prefs() { return getSharedPreferences("ds-pad", MODE_PRIVATE); }
    float padScale() { return prefs().getFloat("scale", 1f); }
    float padAlpha() { return prefs().getFloat("alpha", 1f); }

    // Diálogo con dos deslizadores: tamaño (60-140 %) y opacidad (15-100 %), aplicados al momento
    void editPad() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(48, 24, 48, 0);
        android.widget.SeekBar size = new android.widget.SeekBar(this), op = new android.widget.SeekBar(this);
        size.setMax(80); size.setProgress(Math.round(padScale() * 100) - 60);
        op.setMax(85); op.setProgress(Math.round(padAlpha() * 100) - 15);
        TextView t1 = new TextView(this), t2 = new TextView(this);
        t1.setText("Tamaño"); t2.setText("Opacidad");
        box.addView(t1); box.addView(size); box.addView(t2); box.addView(op);
        android.widget.SeekBar.OnSeekBarChangeListener live = new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                prefs().edit().putFloat("scale", (size.getProgress() + 60) / 100f).putFloat("alpha", (op.getProgress() + 15) / 100f).apply();
                pad.setAlpha(padAlpha()); pad.requestLayout(); pad.relayout();
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        };
        size.setOnSeekBarChangeListener(live); op.setOnSeekBarChangeListener(live);
        new android.app.AlertDialog.Builder(this).setTitle("Controles").setView(box)
            .setPositiveButton("Listo", null)
            .setNeutralButton("Restablecer", (d, w) -> { prefs().edit().clear().apply(); pad.setAlpha(1f); pad.relayout(); })
            .show();
    }
    void pushInput() { nativeInput(buttons | padMask | hatMask, ptrX, ptrY, ptrDown); }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int id = MainActivity.retroId(e.getKeyCode());
        if (id < 0 || id > 11 || !MainActivity.isPad(e)) return super.dispatchKeyEvent(e);
        padUsed();
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) padMask |= 1 << id;
        else if (e.getAction() == android.view.KeyEvent.ACTION_UP) padMask &= ~(1 << id);
        pushInput();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!MainActivity.isPad(e) || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        // Cruceta "hat" o stick izquierdo -> cruceta del DS
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f) x = e.getAxisValue(MotionEvent.AXIS_X);
        if (Math.abs(y) < 0.5f) y = e.getAxisValue(MotionEvent.AXIS_Y);
        hatMask = (y < -0.5f ? 1 << UP : 0) | (y > 0.5f ? 1 << DOWN : 0) | (x < -0.5f ? 1 << LEFT : 0) | (x > 0.5f ? 1 << RIGHT : 0)
            | (Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f ? 1 << L : 0)
            | (Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f ? 1 << R : 0);
        if (hatMask != 0) padUsed();
        pushInput();
        return true;
    }

    // El núcleo necesita un archivo real: se copia la ROM una vez a la memoria de la app (y así la partida se llama como el juego)
    File localRom(Uri uri, String name) throws Exception {
        File dir = new File(getFilesDir(), "nds"); dir.mkdirs();
        File f = new File(dir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        long size = -1;
        try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) { size = pfd.getStatSize(); }
        if (f.exists() && f.length() == size) return f;
        File tmp = new File(dir, f.getName() + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(f)) throw new Exception("no se pudo guardar la ROM");
        return f;
    }

    void run(Uri uri, String name) {
        AudioTrack track = null;
        try {
            File rom = localRom(uri, name);
            File sys = new File(getFilesDir(), "ds-system"), saves = new File(getFilesDir(), "ds-saves");
            sys.mkdirs(); saves.mkdirs();
            String core = getApplicationInfo().nativeLibraryDir + "/libmelondsds_libretro.so";
            double rate = nativeStart(core, rom.getAbsolutePath(), sys.getAbsolutePath(), saves.getAbsolutePath());
            if (rate <= 0) throw new Exception("el emulador no pudo cargar el juego");
            File states = new File(getFilesDir(), "ds-states"); states.mkdirs();
            stateFile = new File(states, rom.getName() + ".state");
            runOnUiThread(() -> {
                status.setVisibility(View.GONE);
                if (stateFile.exists()) offerContinue();
            });

            int sr = (int) Math.round(rate);
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                min * 2, AudioTrack.MODE_STREAM); // búfer pequeño: el emulador avanza en ráfagas cortas (menos retraso de los controles)
            track.play();
            short[] audio = new short[16384];
            long frames = 0;
            while (running) {
                // Guardar ANTES de comprobar la pausa: onPause activa ambos a la vez y hay que guardar ya
                if (saveNow) { saveNow = false; nativeSave(); nativeState(stateFile.getAbsolutePath(), true); }
                if (paused) { Thread.sleep(50); continue; }
                if (loadState) { loadState = false; nativeState(stateFile.getAbsolutePath(), false); }
                int n = nativeRunFrame(audio);
                // La escritura bloqueante del audio marca el ritmo (velocidad real); sin audio, ~60 fps a mano
                if (n > 0) track.write(audio, 0, n); else Thread.sleep(16);
                if (++frames % 1800 == 0) nativeSave(); // guardado automático cada ~30 s
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            runOnUiThread(() -> { Toast.makeText(this, "DS: " + msg, Toast.LENGTH_LONG).show(); finish(); });
        } finally {
            if (track != null) { track.stop(); track.release(); }
            if (stateFile != null) nativeState(stateFile.getAbsolutePath(), true); // autoguardado al cerrar
            nativeStop(); // guarda la partida y descarga el núcleo
        }
    }

    // Botón "Continuar" (10 s) si hay un autoguardado de la última vez
    void offerContinue() {
        android.widget.Button b = new android.widget.Button(this);
        b.setText("▶ Continuar donde lo dejaste");
        b.setBackgroundColor(Color.rgb(51, 170, 119)); b.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 24;
        ((FrameLayout) status.getParent()).addView(b, lp);
        b.setOnClickListener(v -> { loadState = true; ((FrameLayout) b.getParent()).removeView(b); });
        b.postDelayed(() -> { if (b.getParent() != null) ((FrameLayout) b.getParent()).removeView(b); }, 10000);
    }

    // Salir de la app o del juego: guardar partida + estado en el hilo del emulador y pausar
    @Override protected void onPause() { super.onPause(); saveNow = true; paused = true; }
    @Override protected void onResume() { super.onResume(); paused = false; immersive(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (emu != null) try { emu.join(3000); } catch (InterruptedException ignored) {}
    }
    @Override public void onBackPressed() { finish(); }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // Mando táctil dibujado a mano: multitáctil real (cada dedo cuenta) y deslizar entre botones
    class Pad extends View {
        final String[] labels = {"L", "R", "▲", "▼", "◀", "▶", "A", "B", "X", "Y", "SELECT", "START"};
        // DsActivity.X/Y explícitos: dentro de una View, "X" e "Y" serían View.X / View.Y
        final int[] bits = {L, R, UP, DOWN, LEFT, RIGHT, A, B, DsActivity.X, DsActivity.Y, SELECT, START};
        final RectF[] rects = new RectF[labels.length];
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pad(Context c) {
            super(c);
            text.setColor(Color.WHITE); text.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < rects.length; i++) rects[i] = new RectF();
        }

        void relayout() { onSizeChanged(getWidth(), getHeight(), 0, 0); invalidate(); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float u = Math.min(w / 9f, h / 4.2f) * padScale(); // unidad de tamaño (× tamaño elegido en ✎)
            float cy = h * 0.58f, lx = w * 0.2f, rx = w * 0.8f;
            set(0, w * 0.02f, 0, w * 0.3f, u * 0.8f);          // L
            set(1, w * 0.7f, 0, w * 0.98f, u * 0.8f);          // R
            box(2, lx, cy - u * 1.1f, u); box(3, lx, cy + u * 1.1f, u);   // arriba / abajo
            box(4, lx - u * 1.1f, cy, u); box(5, lx + u * 1.1f, cy, u);   // izquierda / derecha
            box(6, rx + u * 1.1f, cy, u); box(7, rx, cy + u * 1.1f, u);   // A (derecha) / B (abajo)
            box(8, rx, cy - u * 1.1f, u); box(9, rx - u * 1.1f, cy, u);   // X (arriba) / Y (izquierda)
            set(10, w * 0.36f, h - u * 0.9f, w * 0.49f, h - u * 0.2f);   // SELECT
            set(11, w * 0.51f, h - u * 0.9f, w * 0.64f, h - u * 0.2f);   // START
            text.setTextSize(u * 0.38f);
        }
        void set(int i, float l, float t, float r, float b) { rects[i].set(l, t, r, b); }
        void box(int i, float cx, float cy, float u) { rects[i].set(cx - u * 0.52f, cy - u * 0.52f, cx + u * 0.52f, cy + u * 0.52f); }

        @Override protected void onDraw(Canvas c) {
            for (int i = 0; i < rects.length; i++) {
                fill.setColor((buttons >> bits[i] & 1) != 0 ? Color.rgb(51, 170, 119) : Color.argb(140, 70, 70, 80));
                boolean round = i >= 6 && i <= 9;
                if (round) c.drawOval(rects[i], fill); else c.drawRoundRect(rects[i], 18, 18, fill);
                c.drawText(labels[i], rects[i].centerX(), rects[i].centerY() + text.getTextSize() / 3, text);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            setAlpha(padAlpha()); // al tocarlo vuelve a su opacidad elegida
            int mask = 0, act = e.getActionMasked();
            for (int p = 0; p < e.getPointerCount(); p++) {
                // El dedo que se levanta en este evento ya no cuenta
                if ((act == MotionEvent.ACTION_POINTER_UP && p == e.getActionIndex()) || act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) continue;
                for (int i = 0; i < rects.length; i++) if (rects[i].contains(e.getX(p), e.getY(p))) mask |= 1 << bits[i];
            }
            if (mask != buttons && Integer.bitCount(mask) > Integer.bitCount(buttons)) performHapticFeedback(1); // vibración corta al pulsar
            buttons = mask;
            pushInput();
            invalidate();
            return true;
        }
    }
}
