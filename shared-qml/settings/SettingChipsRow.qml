import QtQuick
import miuix.Core
import design
import "."

// SettingSpec.CHIPS（2026-11-15 用户要求）：把 20 多项"接法"排成**一张卡里的芯片网格**，
// 而不是一条长下拉 —— 下拉里排成一列完全看不了。
//
// 语义与 dropdown 完全一致：存的是 options 的下标 ✓ 点一下就选 ✓ 选中的那颗高亮。
Item {
    id: row
    property var spec: null
    property bool separator: false
    objectName: spec ? "setting_" + spec.key : ""
    property string providerText: spec && spec.provider && spec.provider.length > 0 ? settings.info(spec.provider) : ""
    function descText() {
        if (providerText.length > 0) return providerText
        return spec ? i18n.t(spec.desc) : ""
    }

    property var labels: {
        var out = []
        if (row.spec) for (var i = 0; i < row.spec.options.length; i++)
            out.push(i18n.t(row.spec.options[i]))
        return out
    }
    property int selected: row.spec ? Number(settings.value(row.spec.key)) : 0

    // 宽窗三列、窄窗两列（**手机上也必须多列**：23 项排成一列根本没法看 ✗
    // 2026-11-15 用户第二次反馈后才定成这个规则）
    readonly property int columns: row.width >= 560 ? 3 : 2
    readonly property int count: row.labels.length
    readonly property int rowsCount: count <= 0 ? 0 : Math.ceil(count / columns)
    readonly property real cellH: 36
    readonly property real gap: Ui.space2
    readonly property real cellW: Math.max(72, (row.width - Ui.space4 * 2 - (columns - 1) * gap) / columns)

    implicitHeight: frame.implicitHeight + (rowsCount > 0
        ? Ui.space2 + rowsCount * (cellH + gap) + Ui.space2 : Ui.space2)

    SettingRowFrame {
        id: frame
        width: row.width
        title: row.spec ? i18n.t(row.spec.title) : ""
        summary: row.descText()
        separator: row.separator
        clickable: false
        controlWidth: 0
    }

    Repeater {
        model: row.count
        Button {
            readonly property int idx: index
            x: Ui.space4 + (idx % row.columns) * (row.cellW + row.gap)
            y: frame.height + Ui.space2 + Math.floor(idx / row.columns) * (row.cellH + row.gap)
            width: row.cellW
            height: row.cellH
            objectName: "settingChip_" + idx
            type: idx === row.selected ? "filledTonal" : "outlined"
            text: row.labels[idx]
            // 注意：miuix 的 Button 没有 contentElide / font.* 这两个属性 ✗
            // 长名字靠 Button 自己的单行截断（它内部 Text 是 elide 的）
            onClicked: settings.setValue(row.spec.key, idx)
        }
    }
}
