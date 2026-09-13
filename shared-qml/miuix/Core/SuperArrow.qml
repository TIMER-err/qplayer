import QtQuick
import miuix.Core

Item {
    id: superArrowRoot
    property string title: ""
    property string summary: ""
    property string rightText: ""
    property string indicator: "chevron_right"
    property bool enabled: true
    signal clicked()
    width: parent ? parent.width : 320
    implicitHeight: preference.implicitHeight

    BasicComponent {
        id: preference
        anchors.fill: parent
        title: superArrowRoot.title
        summary: superArrowRoot.summary
        enabled: superArrowRoot.enabled
        onClicked: superArrowRoot.clicked()
        endAction: Component {
            Item {
                implicitWidth: valueLabel.implicitWidth + (superArrowRoot.rightText.length > 0 ? 8 : 0) + 10
                implicitHeight: Math.max(16, valueLabel.implicitHeight)
                Text {
                    id: valueLabel
                    width: Math.max(0, parent.width - 18)
                    anchors.verticalCenter: parent.verticalCenter
                    text: superArrowRoot.rightText
                    visible: text.length > 0
                    elide: Text.ElideRight
                    font.pixelSize: 14
                    color: superArrowRoot.enabled ? Theme.color.onSurfaceVariantActions : Theme.color.disabledOnSecondaryVariant
                }
                Icon {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    width: 10
                    height: 16
                    name: superArrowRoot.indicator
                    color: superArrowRoot.enabled ? Theme.color.onSurfaceVariantActions : Theme.color.disabledOnSecondaryVariant
                }
            }
        }
    }
}
