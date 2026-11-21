package dev.t1m3.qplayer.android.audio.output;

import dev.t1m3.qplayer.audio.output.OutputDevice;
import dev.t1m3.qplayer.audio.output.OutputDevices;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * 安卓端的输出设备来源。
 *
 * <p>对应 Sonalis 顶层的 {@code cpal::default_host()} / {@code list_output_devices()} /
 * {@code default_output_device()}，以及桌面端 {@code JavaSoundOutputProvider} 的同一位置。
 * 宿主在启动时装一次（见 {@code QPlayerApplication.onCreate}）：
 *
 * <pre>
 * OutputDevices.install(new AndroidOutputProvider(this));
 * </pre>
 *
 * <h3>为什么只有一台设备</h3>
 *
 * <p>{@code javax.sound.sampled} 那边能枚举出多个混音器，但安卓的 {@code AudioTrack}
 * <strong>没有「选设备」这回事</strong>：它总是播到系统当前的输出路由上
 * （扬声器/耳机/蓝牙由 AudioService 决定，耳机插拔会自动切换）。
 * {@code AudioTrack.setPreferredDevice(AudioDeviceInfo)} 是唯一能把流钉到某条路由上的办法
 * （API 23+），但那要设备 id 参与开流，而这次移植的目标是「一条永不中断的输出流」，
 * 让路由随插拔自动走反而更符合平台习惯。
 *
 * <p>所以这里<strong>如实只报一台设备</strong>，不把 {@code AudioManager.getDevices()}
 * 报出来的条目伪装成「可选设备」——那会让设置页出现选了也不生效的项。
 *
 * <h3>「没有可用设备」也必须能活</h3>
 *
 * <p>与桌面端同一条约定：{@link #list()} 与 {@link #defaultDevice()} <strong>不抛异常</strong>。
 * 探测是界面初始化时必走的一步，它抛异常等于整个设置页打不开。
 */
public final class AndroidOutputProvider implements OutputDevices.Provider {

    private final Context appContext;
    /** 单台设备缓存：保证 {@code list()} 与 {@code defaultDevice()} 返回同一个对象（相等性靠它）。 */
    private volatile AndroidOutputDevice device;

    public AndroidOutputProvider(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("AndroidOutputProvider 需要 Context");
        }
        // 只留 application context：设备对象会活得比 Activity 久（静态安装），
        // 抓着 Activity 不放就是一处泄漏
        this.appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
    }

    private AndroidOutputDevice device() {
        AndroidOutputDevice local = device;
        if (local == null) {
            local = new AndroidOutputDevice(appContext);
            device = local;
        }
        return local;
    }

    @Override
    public List<OutputDevice> list() {
        try {
            return Collections.<OutputDevice>singletonList(device());
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public OutputDevice defaultDevice() {
        try {
            return device();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "AndroidOutputProvider{" + appContext.getPackageName() + '}';
    }
}
