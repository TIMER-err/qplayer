package dev.t1m3.qplayer.android.recognize;

import android.content.Context;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.netease.AudioMatchClient;
import dev.t1m3.qplayer.util.Logger;

/**
 * 听歌识曲的安卓实现（移植自她的 {@code recognize/} 包 + Compose 的 RecognizeDialog）。
 *
 * <p>做成 {@link PlayerController.RecognitionLauncher}：宿主把本对象塞给 controller，
 * QML 只认 {@code player.startRecognize()} 与那几个 Property。**本类不做线程回投** ——
 * 回调允许来自识别线程，回投由 controller 的 {@code post} 统一负责（同一个 Property
 * 只能在一个地方被写，避免两条回投路径互相盖掉）。
 */
public final class AndroidSongRecognizer implements PlayerController.RecognitionLauncher {

    private final Context context;
    private AfpFingerprint fingerprint;
    private SongRecognizer recognizer;

    public AndroidSongRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void start(PlayerController.RecognitionCallback callback) {
        if (recognizer != null) return;
        try {
            if (fingerprint == null) fingerprint = new AfpFingerprint(context);
            SongRecognizer created = new SongRecognizer(fingerprint, new SongRecognizer.Listener() {
                @Override public void onListening(float seconds, String note) {
                    callback.onUpdate(1, note, null, null);
                }

                @Override public void onMatching(float seconds) {
                    callback.onUpdate(2, null, null, null);
                }

                @Override public void onFound(AudioMatchClient.Match match) {
                    recognizer = null;
                    callback.onUpdate(3, null, match == null ? null : String.valueOf(match), null);
                }

                @Override public void onFailed(String why) {
                    recognizer = null;
                    callback.onUpdate(4, why, null, null);
                }
            });
            recognizer = created;
            created.start();
        } catch (Throwable error) {
            Logger.warn("song recognition failed to start: {}", error.toString());
            recognizer = null;
            callback.onUpdate(4, error.toString(), null, null);
        }
    }

    @Override
    public void cancel() {
        SongRecognizer current = recognizer;
        recognizer = null;
        if (current != null) current.cancel();
    }
}
