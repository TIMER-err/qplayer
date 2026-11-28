package dev.t1m3.qplayer.android.update;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Request a system-verified self-update, never a background confirmation dialog. */
public final class SilentApkInstaller {
    private static final String CALLBACK = "dev.t1m3.qplayer.SILENT_APK_RESULT";

    public static boolean supported(Context context) {
        int sdk = Build.VERSION.SDK_INT;
        return sdk >= 31 && context.getPackageManager().canRequestPackageInstalls();
    }

    /** Called off the UI thread only after the user left an idle player. */
    public static boolean install(Context context, Path apk, BooleanSupplier allowed)
            throws IOException, PackageManager.NameNotFoundException {
        if (!allowed.getAsBoolean() || !supported(context)) return false;
        PackageManager pm = context.getPackageManager();
        String name = context.getPackageName();
        PackageInfo incoming = pm.getPackageArchiveInfo(apk.toString(), 0);
        if (incoming == null || !name.equals(incoming.packageName)
                || incoming.getLongVersionCode() <= pm.getPackageInfo(name, 0).getLongVersionCode())
            throw new IOException("Update APK is not a newer build of this application");

        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(name);
        params.setSize(Files.size(apk));
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = Files.newInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, Files.size(apk))) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (!allowed.getAsBoolean()) return false;
                    out.write(buffer, 0, n);
                }
                session.fsync(out);
            }
            if (!allowed.getAsBoolean()) return false;
            Intent callback = new Intent(context, Result.class).setAction(CALLBACK);
            PendingIntent result = PendingIntent.getBroadcast(context, id, callback,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(result.getIntentSender());
            committed = true;
            return true;
        } finally {
            if (!committed) installer.abandonSession(id);
        }
    }

    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!CALLBACK.equals(intent.getAction())) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Users with ineligible install sources or device policy keep the manual update path.
                int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                if (id >= 0) {
                    try { context.getPackageManager().getPackageInstaller().abandonSession(id); }
                    catch (SecurityException | IllegalStateException ignored) { }
                }
            }
            android.util.Log.i("qplayer.update", "Background installation status: " + status);
        }
    }

    private SilentApkInstaller() {}
}
