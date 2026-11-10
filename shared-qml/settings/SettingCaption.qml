import QtQuick
import design

// Caption above a settings card ("界面与主题", "已安装"): small, bold and quiet.
// Callers indent it to the rows' text column (x / Layout.leftMargin = 16) so the
// eye runs straight down the list.
Text {
    color: Ui.textSecondary
    elide: Text.ElideRight
    maximumLineCount: 1
    font.pixelSize: Ui.textCaption
    font.weight: Ui.weightBold
}
