package dev.t1m3.qplayer.desktop.audio.output;

import dev.t1m3.qplayer.audio.output.DeviceCapabilities;
import dev.t1m3.qplayer.audio.output.OutputDevice;
import dev.t1m3.qplayer.audio.output.OutputDevices;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面端的输出设备来源：{@code javax.sound.sampled} 里所有能放音的混音器。
 *
 * <p>对应 Sonalis 顶层的 {@code cpal::default_host()} 与
 * {@code list_output_devices()} / {@code default_output_device()}。
 *
 * <p>宿主在启动时装一次：
 *
 * <pre>
 * OutputDevices.install(new JavaSoundOutputProvider());
 * </pre>
 *
 * <h3>「没有可用声卡」也必须能活</h3>
 *
 * 枚举路径上的任何异常都<strong>不往外抛</strong>：拿不到混音器清单就返回空列表，
 * 找不到默认设备就返回 {@code null}。理由很实际——探测是界面初始化时必走的一步，
 * 让它在没有声卡的机器上抛异常，等于整个设置页打不开。
 */
public final class JavaSoundOutputProvider implements OutputDevices.Provider {

    @Override
    public List<OutputDevice> list() {
        List<OutputDevice> devices = new ArrayList<OutputDevice>();
        Mixer.Info[] infos;
        try {
            infos = AudioSystem.getMixerInfo();
        } catch (Throwable t) {
            // 没有音频系统（无头环境、驱动缺失）：空列表，不抛
            return devices;
        }
        if (infos == null) {
            return devices;
        }
        for (int i = 0; i < infos.length; i++) {
            JavaSoundOutputDevice device = new JavaSoundOutputDevice(infos[i]);
            // 只留下「声明过至少一种输出格式」的混音器：Port 混音器（音量控制那种）
            // 没有任何 SourceDataLine，列进设备下拉框只会让用户选到一个放不出声的项
            DeviceCapabilities caps = device.capabilities();
            if (caps.isEmpty() && caps.declaredFormats().isEmpty()) {
                continue;
            }
            devices.add(device);
        }
        return devices;
    }

    /**
     * 系统默认输出设备，没有可用设备时返回 {@code null}。
     *
     * <p>默认混音器问的是 {@code AudioSystem.getMixer(null)}（Java Sound 认的默认值），
     * 找不到就退到列表里的第一台。
     */
    @Override
    public OutputDevice defaultDevice() {
        List<OutputDevice> devices = list();
        if (devices.isEmpty()) {
            return null;
        }
        String name = null;
        try {
            Mixer defaultMixer = AudioSystem.getMixer(null);
            if (defaultMixer != null) {
                name = defaultMixer.getMixerInfo().getName();
            }
        } catch (Throwable ignored) {
            // 问不到默认混音器就退到第一台
        }
        if (name != null) {
            for (int i = 0; i < devices.size(); i++) {
                if (name.equals(devices.get(i).name())) {
                    return devices.get(i);
                }
            }
        }
        return devices.get(0);
    }
}
