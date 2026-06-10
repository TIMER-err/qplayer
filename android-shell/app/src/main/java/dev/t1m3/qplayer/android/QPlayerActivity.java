package dev.t1m3.qplayer.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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
    private QmlGLSurfaceView glView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Cookies / config live in app-private storage on Android.
        AppDirs.setBase(getFilesDir().getAbsolutePath());

        AudioBackend backend = new AndroidAudioBackend();
        MetadataReader reader = new AndroidMetadataReader();
        controller = new PlayerController(backend, reader);

        String qml;
        try {
            qml = readAsset("Main.qml");
        } catch (IOException e) {
            throw new RuntimeException("failed to read Main.qml", e);
        }

        QmlEngine engine = new QmlEngine(new DexClassLoaderBackend(getClass().getClassLoader()));
        float density = getResources().getDisplayMetrics().density;
        glView = new QmlGLSurfaceView(this, engine, qml,
                new AssetResourceLoader(getAssets()), density);
        glView.setController(controller);
        setContentView(glView);

        controller.loadHome();
        requestAudioPermission();
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

    private void scanMusic() {
        String music = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC).getAbsolutePath();
        controller.scan(music);
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
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
