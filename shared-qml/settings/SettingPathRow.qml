import QtQuick
import miuix.Core

SuperArrow {
    property var spec: null
    property string selectedPath: spec ? String(settings.value(spec.key) || "") : ""
    title: spec ? i18n.t(spec.title) : ""
    summary: selectedPath.length > 0 ? selectedPath : (spec ? i18n.t(spec.desc) : "")
    rightText: i18n.t("settings.folder.choose")
    onClicked: if (spec) settings.pickDirectory(spec.key)
}
