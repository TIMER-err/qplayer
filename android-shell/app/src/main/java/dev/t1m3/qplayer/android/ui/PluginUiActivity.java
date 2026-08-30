package dev.t1m3.qplayer.android.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import dev.t1m3.qplayer.bridge.PlayerController;
import io.github.timer_err.qml4j.android.QmlGLSurfaceView;

/** Full-screen Android host for one independently sandboxed plugin QML scene. */
public final class PluginUiActivity extends Activity {
    public static final String EXTRA_PLUGIN_ID = "pluginId";
    public static final String EXTRA_CONTRIBUTION_ID = "contributionId";
    private QmlGLSurfaceView surface;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PlayerController controller = QPlayerActivity.sharedController();
        String pluginId = getIntent().getStringExtra(EXTRA_PLUGIN_ID);
        String contributionId = getIntent().getStringExtra(EXTRA_CONTRIBUTION_ID);
        if (controller == null || pluginId == null || contributionId == null) {
            finish();
            return;
        }
        float scale = getResources().getDisplayMetrics().density;
        surface = new QmlGLSurfaceView(this,
                () -> controller.createPluginUiSession(pluginId, contributionId), scale);
        surface.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        surface.setErrorListener(trace -> runOnUiThread(this::finish));
        setContentView(surface);
    }

    @Override protected void onResume() {
        super.onResume();
        if (surface != null) surface.onResume();
    }

    @Override protected void onPause() {
        if (surface != null) surface.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (surface != null) surface.dispose();
        surface = null;
        super.onDestroy();
    }
}
