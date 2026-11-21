package dev.t1m3.qplayer.audio.output;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * 「播放设备」的选中逻辑（设置页那一行背后的事实）。
 *
 * <p>判据要钉的三件事，每一件都在真机上会以某种难看的形式出现：
 *
 * <ul>
 *   <li><b>选了就用它</b>：引擎开流时问 {@link OutputDevices#defaultDevice()}，选中的那台优先；</li>
 *   <li><b>它没了就退回系统默认</b>：耳机拔了之后必须还能出声，而不是"点了播放没反应"
 *       （那种失败在界面上只表现为没反应，查起来最费劲）；</li>
 *   <li><b>跟随系统默认</b>（{@code null}）时不许返回任何被选中的残留。</li>
 * </ul>
 *
 * <p>存的是**名字**不是下标：枚举顺序会变（插拔、驱动重排），下标下次启动会指着别的设备。
 */
public class OutputDeviceSelectionTest {

    private static OutputDevice device(String name) {
        return (OutputDevice) Proxy.newProxyInstance(OutputDevice.class.getClassLoader(),
                new Class<?>[]{OutputDevice.class},
                (proxy, method, args) -> {
                    if ("name".equals(method.getName())) return name;
                    if ("toString".equals(method.getName())) return "device:" + name;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    private static void install(String... names) {
        List<OutputDevice> devices = new ArrayList<OutputDevice>();
        for (String n : names) {
            devices.add(device(n));
        }
        OutputDevices.install(new OutputDevices.Provider() {
            @Override
            public List<OutputDevice> list() {
                return devices;
            }

            @Override
            public OutputDevice defaultDevice() {
                return devices.isEmpty() ? null : devices.get(0);
            }
        });
    }

    @After
    public void reset() {
        OutputDevices.select(null);
        OutputDevices.install(null);
    }

    @Test
    public void theSelectedDeviceWinsOverTheSystemDefault() {
        install("扬声器", "USB 声卡");
        assertSame("没选时是系统默认那台", "扬声器", OutputDevices.defaultDevice().name());

        OutputDevices.select("USB 声卡");
        assertEquals("选了就按选的走", "USB 声卡", OutputDevices.defaultDevice().name());
        assertEquals("名字要留着（下次启动靠它）", "USB 声卡", OutputDevices.selectedName());

        OutputDevices.select(null);
        assertEquals("回到跟随系统默认", "扬声器", OutputDevices.defaultDevice().name());
        assertNull(OutputDevices.selectedName());
    }

    @Test
    public void aDeviceThatWentAwayFallsBackToTheSystemDefault() {
        install("扬声器", "USB 声卡");
        OutputDevices.select("USB 声卡");
        // 拔掉：列表里没有它了
        install("扬声器");
        assertEquals("拔了就要退回系统默认（不能变成「打开输出失败」）",
                "扬声器", OutputDevices.defaultDevice().name());
    }

    @Test
    public void anEmptySelectionNeverMatchesADevice() {
        install("扬声器");
        OutputDevices.select("");
        assertNull("空串等于没选", OutputDevices.selectedName());
        assertNull("空串不该匹配到任何设备", OutputDevices.selectedDevice());
        assertEquals("扬声器", OutputDevices.defaultDevice().name());
    }
}
