package dev.t1m3.qplayer.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;

import io.github.timer_err.qml4j.android.AssetResourceLoader;
import io.github.timer_err.qml4j.android.DexClassLoaderBackend;
import io.github.timer_err.qml4j.android.QmlGLSurfaceView;
import io.github.timer_err.qml4j.engine.QmlEngine;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.store.AppDirs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Music player entry point: builds the {@link PlayerController} over the
 * Android audio backend, hands it to a {@link QmlGLSurfaceView} running
 * {@code Main.qml}, and kicks off a scan of the device music folder once the
 * audio-read permission is granted.
 */
public final class QPlayerActivity extends Activity {

    static {
        // Same Skija JNI bring-up as the qml4j demo: we ship the .so via
        // jniLibs and bypass the auto-loader, so _nAfterLoad() must run
        // explicitly or every object-returning native call crashes.
        System.setProperty("skija.staticLoad", "false");
        System.loadLibrary("skija");
        io.github.humbleui.skija.impl.Library._nAfterLoad();
        try {
            Class.forName("io.github.humbleui.skija.ImageInfo");
            Class.forName("io.github.humbleui.skija.ColorInfo");
            Class.forName("io.github.humbleui.skija.ColorSpace");
            Class.forName("io.github.humbleui.skija.Color4f");
            Class.forName("io.github.humbleui.skija.Image");
            Class.forName("io.github.humbleui.skija.Canvas");
            Class.forName("io.github.humbleui.skija.Paint");
            Class.forName("io.github.humbleui.skija.Font");
            Class.forName("io.github.humbleui.types.Rect");
            Class.forName("io.github.humbleui.types.IRect");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static final int REQ_AUDIO = 1;

    private PlayerController controller;
    private AppSettings settings;
    private QmlGLSurfaceView glView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Cookies / config live in app-private storage on Android.
        AppDirs.setBase(getFilesDir().getAbsolutePath());

        AudioBackend backend = new AndroidAudioBackend();
        MetadataReader reader = new AndroidMetadataReader();
        controller = new PlayerController(backend, reader);
        controller.setColorExtractor(new AndroidColorExtractor());

        settings = new AppSettings();
        settings.setDarkListener(dark -> runOnUiThread(() -> applySystemBars(dark)));
        settings.load(this);
        applySystemBars(settings.resolvedDarkValue());

        String qml;
        try {
            qml = readAsset("Main.qml");
        } catch (IOException e) {
            throw new RuntimeException("failed to read Main.qml", e);
        }

        // Lyric renderer fonts: bundled Roboto from assets (FontMgr has no
        // usable default face on Android, so load real TTFs).
        try {
            dev.t1m3.qplayer.android.lyric.Fonts.init(
                    readAssetBytes("fonts/Roboto-Regular.ttf"),
                    readAssetBytes("fonts/Roboto-Medium.ttf"));
            // Material Symbols for the host-drawn lyric transport icons (drawn by
            // shaped ligature name, same as the QML scene's icons).
            dev.t1m3.qplayer.android.lyric.Fonts.initIcon(
                    readAssetBytes("fonts/MaterialSymbolsOutlined.ttf"));
        } catch (IOException ignored) {
        }

        QmlEngine engine = new QmlEngine(new DexClassLoaderBackend(getClass().getClassLoader()));
        float density = getResources().getDisplayMetrics().density;
        glView = new QmlGLSurfaceView(this, engine, qml,
                new AssetResourceLoader(getAssets()), density);
        glView.setController(controller);
        glView.setSettings(settings);
        glView.setErrorListener(trace -> runOnUiThread(() -> showError(trace)));

        // glView under a splash overlay shown while the QML tree compiles (slow
        // on first launch: parse -> bytecode -> dex). The splash advances with
        // per-component compile progress and fades out at the first painted frame.
        android.widget.FrameLayout rootView = new android.widget.FrameLayout(this);
        rootView.addView(glView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        splashView = buildSplash();
        rootView.addView(splashView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        glView.setSplashListener(new QmlGLSurfaceView.SplashListener() {
            @Override public void onProgress(String name, int count) {
                runOnUiThread(() -> splashStatus.setText("正在编译界面组件… " + count));
            }
            @Override public void onReady() {
                runOnUiThread(QPlayerActivity.this::hideSplash);
            }
        });
        setContentView(rootView);

        controller.loadHome();
        requestAudioPermission();
    }

    private android.view.View splashView;
    private android.widget.TextView splashStatus;

    private android.view.View buildSplash() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setBackgroundColor(0xFF1A1F26);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("qplayer");
        title.setTextColor(0xFFE6E1E5);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34);
        title.setGravity(android.view.Gravity.CENTER);
        box.addView(title);

        android.widget.ProgressBar bar = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().density * 200),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Math.round(getResources().getDisplayMetrics().density * 24);
        bar.setLayoutParams(blp);
        box.addView(bar);

        splashStatus = new android.widget.TextView(this);
        splashStatus.setText("正在加载…");
        splashStatus.setTextColor(0xFF9A9499);
        splashStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        splashStatus.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Math.round(getResources().getDisplayMetrics().density * 12);
        splashStatus.setLayoutParams(slp);
        box.addView(splashStatus);

        return box;
    }

    private void hideSplash() {
        if (splashView == null) return;
        final android.view.View v = splashView;
        splashView = null;
        v.animate().alpha(0f).setDuration(280).withEndAction(() -> {
            android.view.ViewParent p = v.getParent();
            if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(v);
        }).start();
    }

    private void requestAudioPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            scanMusic();
        } else {
            requestPermissions(new String[]{perm}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanMusic();
        }
    }

    /** Paint the status + navigation bars with a theme-appropriate surface and flip
     *  the bar-icon contrast so they read on either light or dark backgrounds. */
    private void applySystemBars(boolean dark) {
        int surface = dark ? 0xFF141218 : 0xFFFEF7FF;
        android.view.Window w = getWindow();
        w.setStatusBarColor(surface);
        w.setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            android.view.View dv = w.getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (dark) {
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            dv.setSystemUiVisibility(flags);
        }
    }

    private void showError(String trace) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(trace);
        tv.setTextSize(11f);
        tv.setTextIsSelectable(true);
        tv.setPadding(24, 24, 24, 24);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    private void scanMusic() {
        String music = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC).getAbsolutePath();
        controller.scan(music);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (glView != null) {
            glView.onSystemNightChanged(AppSettings.isSystemDark(this));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glView != null) glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null) controller.shutdown();
    }

    private String readAsset(String name) throws IOException {
        return new String(readAssetBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] readAssetBytes(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
