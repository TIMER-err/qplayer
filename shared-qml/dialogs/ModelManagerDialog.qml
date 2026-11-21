import QtQuick
import miuix.Core

// 模型（按需下载的权重）：每个模型一行——状态 / 体积 / 许可证与来源 / 下载按钮 /
// 失败重试 / 镜像全挂时的手动兜底。
//
// 权重绝不打包进成品（player-core 的 ModelPackagingTest 钉着这一条），所以用户必须
// 在这里看得见「有没有」「多大」「什么许可」「从哪来」，并且点得开下载。
//
// 结构上刻意拍平（qml4j 的已知限制：深层嵌套 Layout 的 fillWidth 传播不可靠），
// 一律用 Column + 显式宽度，子项高度由自己的内容算出来。
Item {
    id: control
    anchors.fill: parent
    property bool active: false
    property bool pickerWasOpen: false
    signal closed()

    // 结构（列哪些模型、各自的静态信息）：只在打开/关闭时重算。
    // 这里**不读** modelRevision —— 那是 Repeater 的 model，跟着进度重建整个列表
    // 会让按钮和文字每 200ms 抖一次。会变的部分在每一行自己的 row 绑定里。
    property var rows: control.active || control.pickerWasOpen ? settings.modelRows() : []

    onActiveChanged: {
        if (active) {
            control.pickerWasOpen = true
            picker.open()
        } else if (picker.opened) {
            picker.close()
        }
    }

    Dialog {
        id: picker
        objectName: "modelManagerDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t("settings.models.dialog.title")
        showAcceptButton: false
        closeOnScrim: false
        rejectText: i18n.t("common.back")
        onClosed: control.closed()

        Column {
            id: body
            // 用 anchors 而不是 `width: picker.contentWidth`：**positioner 会把自己的
            // width 绑定写坏**。qml4j 的 Column.layout() 结尾有一句
            // `if (maxW > width) width.set(maxW)`，而 Property.set() 会 clearBinding()——
            // 对话框刚打开的那一帧 contentWidth 还是 0，列里的子项却已经量出了自然宽度
            // （Row 里那几个 Button），于是这一句把绑定永久换成了「当时最大的子项宽度」，
            // 之后 contentWidth 变成 372 也再也没人听。先例就在 miuix 的 Dialog.qml 里
            // （「older positioners can replace their own width binding while the overlay
            // is still unsized」）。anchors 每个 layout pass 都会重算（Renderer.measure 末尾
            // 的 applyAnchors），所以它不会被写坏。
            anchors.left: parent.left
            anchors.right: parent.right
            spacing: 10

            Text {
                width: parent.width
                text: i18n.t("settings.models.dialog.hint")
                // §1.1: explanatory small print is footnote 13/18.
                font.pixelSize: Theme.typography.footnote.size
                wrapMode: Text.Wrap
                color: Theme.color.onSurfaceVariantColor
            }
            Text {
                width: parent.width
                text: i18n.t("settings.models.folder", settings.modelDir())
                font.pixelSize: Theme.typography.footnote.size
                wrapMode: Text.Wrap
                color: Theme.color.onSurfaceVariantColor
            }

            Repeater {
                model: control.rows

                Item {
                    id: card
                    width: body.width
                    height: inner.height
                    property string modelName: modelData.name
                    // 这一行**此刻**的样子。先读 modelRevision 才会在下载推进、
                    // 校验结束、下载完成时重新求值 —— 只刷新这一行的绑定，
                    // 不重建列表。
                    property var row: {
                        settings.modelRevision
                        return settings.modelRow(card.modelName)
                    }
                    property var info: modelData

                    // 同上：这一列也必须用 anchors。它的 width 绑定曾经被自己的
                    // layout() 写死在 74（= 那一行按钮的自然宽度，因为 Row 里的 Button
                    // 是隐式尺寸），于是卡片 372 宽、里面的文字却按 74 宽换行，
                    // 一列窄字加上比视口还高的内容——正是「文字排版有问题」与
                    // 「取消按钮在视口外面、很难点动」这两条的共同根因。
                    Column {
                        id: inner
                        anchors.left: parent.left
                        anchors.right: parent.right
                        spacing: 6

                        Text {
                            width: parent.width
                            objectName: "modelRowTitle." + card.modelName
                            text: card.info.title
                            // A row's main title is body 17/22 Semibold (§4.4); 16/57 is
                            // not a style the Apple ramp ships.
                            font.pixelSize: Theme.typography.bodyLarge.size
                            font.bold: true   // 插件下载页的行标题就是这个字重（600 太轻）
                            wrapMode: Text.Wrap
                            color: Theme.color.onSurfaceColor
                        }
                        Text {
                            width: parent.width
                            objectName: "modelRowState." + card.modelName
                            text: (card.row ? card.row.stateText : "")
                                  + " · " + i18n.t("settings.models.size", card.info.sizeText)
                            // The row's secondary line is subheadline 15/20 (§4.4).
                            font.pixelSize: Theme.typography.bodyMedium.size
                            wrapMode: Text.Wrap
                            color: card.row && card.row.corrupt
                                ? Theme.color.error : Theme.color.onSurfaceVariantColor
                        }
                        // 信息层级照搬插件下载页：一行只有「标题 + 一条说明 + 操作」。
                        // 许可证 / 上游地址 / 启用的能力 / 清单 note 都是开发者看的元数据，
                        // 原来每行铺六行字，把列表读成了一堵墙；它们仍留在清单里，
                        // 需要时看 model-manifest.json 即可。
                        Text {
                            width: parent.width
                            visible: card.row && card.row.error.length > 0
                            text: card.row ? i18n.t("settings.models.error", card.row.error) : ""
                            font.pixelSize: Theme.typography.footnote.size
                            wrapMode: Text.Wrap
                            color: Theme.color.error
                        }
                        Text {
                            width: parent.width
                            visible: card.row && card.row.mirror.length > 0
                            text: card.row ? i18n.t("settings.models.usedMirror", card.row.mirror) : ""
                            font.pixelSize: Theme.typography.footnote.size
                            wrapMode: Text.Wrap
                            color: Theme.color.onSurfaceVariantColor
                        }

                        // 进度条：只在下载中画出来。
                        Rectangle {
                            objectName: "modelProgress." + card.modelName
                            width: parent.width
                            height: 4
                            radius: 2
                            visible: card.row && card.row.downloading
                            color: Theme.color.outlineVariant
                            Rectangle {
                                objectName: "modelProgressFill." + card.modelName
                                width: parent.width * (card.row ? card.row.percent : 0) / 100
                                height: parent.height
                                radius: 2
                                color: Theme.color.primary
                            }
                        }

                        // 动作按钮的高度是弹窗自己的那一档，不是 Button 的通用阶梯：
                        // §4.16 实测弹窗按钮 **48pt 高、pill**（`SPC:309`「Buttons are
                        // 48pt tall pills」），而通用阶梯只有 28/34/50（`SPC:371`）——
                        // 默认的 medium 是 34，在这里就是「弹窗里的按钮比弹窗自己的按钮
                        // 矮一截」。Dialog 自己的动作行（返回/确定）用的也是这个数，
                        // 所以一个对话框里所有动作按钮同高。圆角不用设：Button 的
                        // _radius 取的是**实时高度**的一半，48 高自然就是 pill。
                        Row {
                            spacing: 8
                            Button {
                                visible: card.row && card.row.canDownload
                                objectName: "modelDownloadButton." + card.modelName
                                height: Theme.metrics.ios27.buttonDialog
                                type: "filledTonal"
                                icon: "download"
                                text: i18n.t(card.row && card.row.corrupt
                                             ? "settings.models.retry" : "settings.models.download")
                                onClicked: settings.downloadModel(card.modelName)
                            }
                            Button {
                                visible: card.row && card.row.canCancel
                                objectName: "modelCancelButton." + card.modelName
                                height: Theme.metrics.ios27.buttonDialog
                                type: "outlined"
                                text: i18n.t("settings.models.cancel")
                                onClicked: settings.cancelModel(card.modelName)
                            }
                            Button {
                                visible: card.row && card.row.canVerify
                                objectName: "modelVerifyButton." + card.modelName
                                height: Theme.metrics.ios27.buttonDialog
                                type: "text"
                                text: i18n.t("settings.models.verify")
                                onClicked: settings.verifyModel(card.modelName)
                            }
                            Button {
                                visible: card.row && card.row.canRemove
                                objectName: "modelRemoveButton." + card.modelName
                                height: Theme.metrics.ios27.buttonDialog
                                type: "text"
                                text: i18n.t("settings.models.remove")
                                onClicked: settings.removeModel(card.modelName)
                            }
                        }

                        // 手动兜底：只在所有镜像都失败之后才出现。
                        // 一个空链接比没有链接更糟，而镜像正常时摆一堆网盘入口
                        // 只会让人以为自动下载不好使。
                        Column {
                            anchors.left: parent.left
                            anchors.right: parent.right
                            spacing: 4
                            visible: card.row && card.row.manualVisible
                                     && card.info.manualLinks.length > 0

                            Text {
                                width: parent.width
                                text: i18n.t("settings.models.manual.title")
                                font.pixelSize: Theme.typography.bodyMedium.size
                                font.weight: Theme.typography.semibold
                                wrapMode: Text.Wrap
                                color: Theme.color.onSurfaceColor
                            }
                            Text {
                                width: parent.width
                                text: i18n.t("settings.models.manual.desc")
                                font.pixelSize: Theme.typography.footnote.size
                                wrapMode: Text.Wrap
                                color: Theme.color.onSurfaceVariantColor
                            }
                            Repeater {
                                model: card.info.manualLinks
                                Item {
                                    width: parent.width
                                    height: linkColumn.height
                                    Column {
                                        id: linkColumn
                                        anchors.left: parent.left
                                        anchors.right: parent.right
                                        Text {
                                            width: parent.width
                                            text: modelData.label + " · " + modelData.url
                                            font.pixelSize: Theme.typography.footnote.size
                                            wrapMode: Text.Wrap
                                            color: Theme.color.primary
                                        }
                                        Text {
                                            width: parent.width
                                            visible: modelData.code.length > 0
                                            text: i18n.t("settings.models.manual.code", modelData.code)
                                            font.pixelSize: Theme.typography.footnote.size
                                            color: Theme.color.onSurfaceVariantColor
                                        }
                                    }
                                    Ripple {
                                        width: parent.width
                                        height: parent.height
                                        // 宿主是没有 radius 的 Item：不给就是直角
                                        clipRadius: Theme.shape.cornerSmall
                                        onClicked: player.openExternalUrl(modelData.url)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
