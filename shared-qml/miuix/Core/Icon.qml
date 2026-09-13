// Basic vector paths adapted from compose-miuix-ui/miuix (Apache-2.0).
// Copyright 2025 compose-miuix-ui contributors.
// Source commit: 5157b503e86e2bfc2db61db00fff5df41326394a.
import QtQuick
import QtQuick.Shapes
import miuix.Core

Item {
    id: iconRoot
    property string name: ""
    property color color: Theme.color.onSurfaceColor
    implicitWidth: 24
    implicitHeight: 24

    Loader {
        anchors.fill: parent
        sourceComponent: iconRoot.name === "search" ? searchShape
            : iconRoot.name === "chevron_right" ? arrowShape
            : iconRoot.name === "unfold_more" ? arrowsShape
            : iconRoot.name === "check" ? checkShape : null
    }
    Text {
        anchors.centerIn: parent
        visible: iconRoot.name !== "search" && iconRoot.name !== "chevron_right"
            && iconRoot.name !== "unfold_more" && iconRoot.name !== "check"
        text: iconRoot.name
        font.family: Theme.iconFont.name
        font.pixelSize: Math.min(iconRoot.width, iconRoot.height)
        color: iconRoot.color
    }
    Component {
        id: searchShape
        Shape {
            scale: Math.min(iconRoot.width / 20, iconRoot.height / 20)
            transformOrigin: Item.TopLeft
            ShapePath {
                strokeWidth: 0
                strokeColor: "transparent"
                fillColor: iconRoot.color
                PathMove { x: 12.572; y: 13.379 }
                PathCubic { control1X: 11.541; control1Y: 14.183; control2X: 10.244; control2Y: 14.662; x: 8.835; y: 14.662 }
                PathCubic { control1X: 5.477; control1Y: 14.662; control2X: 2.754; control2Y: 11.94; x: 2.754; y: 8.581 }
                PathCubic { control1X: 2.754; control1Y: 5.223; control2X: 5.477; control2Y: 2.5; x: 8.835; y: 2.5 }
                PathCubic { control1X: 12.194; control1Y: 2.5; control2X: 14.916; control2Y: 5.223; x: 14.916; y: 8.581 }
                PathCubic { control1X: 14.916; control1Y: 9.99; control2X: 14.437; control2Y: 11.287; x: 13.633; y: 12.318 }
                PathLine { x: 17.464; y: 16.149 }
                PathCubic { control1X: 17.563; control1Y: 16.248; control2X: 17.612; control2Y: 16.297; x: 17.645; y: 16.346 }
                PathCubic { control1X: 17.78; control1Y: 16.548; control2X: 17.78; control2Y: 16.811; x: 17.645; y: 17.013 }
                PathCubic { control1X: 17.612; control1Y: 17.062; control2X: 17.563; control2Y: 17.111; x: 17.464; y: 17.21 }
                PathCubic { control1X: 17.366; control1Y: 17.308; control2X: 17.316; control2Y: 17.358; x: 17.267; y: 17.39 }
                PathCubic { control1X: 17.065; control1Y: 17.525; control2X: 16.802; control2Y: 17.525; x: 16.601; y: 17.39 }
                PathCubic { control1X: 16.551; control1Y: 17.358; control2X: 16.502; control2Y: 17.308; x: 16.403; y: 17.21 }
                PathLine { x: 12.572; y: 13.379 }
                PathLine { x: 12.572; y: 13.379 }
                PathMove { x: 13.416; y: 8.581 }
                PathCubic { control1X: 13.416; control1Y: 11.111; control2X: 11.365; control2Y: 13.162; x: 8.835; y: 13.162 }
                PathCubic { control1X: 6.305; control1Y: 13.162; control2X: 4.254; control2Y: 11.111; x: 4.254; y: 8.581 }
                PathCubic { control1X: 4.254; control1Y: 6.051; control2X: 6.305; control2Y: 4; x: 8.835; y: 4 }
                PathCubic { control1X: 11.365; control1Y: 4; control2X: 13.416; control2Y: 6.051; x: 13.416; y: 8.581 }
                PathLine { x: 13.416; y: 8.581 }
            }
        }
    }
    Component {
        id: arrowShape
        Shape {
            scale: Math.min(iconRoot.width / 10, iconRoot.height / 16)
            transformOrigin: Item.TopLeft
            ShapePath {
                strokeWidth: 0
                strokeColor: "transparent"
                fillColor: iconRoot.color
                PathMove { x: 1.65; y: 1.469 }
                PathCubic { control1X: 1.929; control1Y: 1.19; control2X: 2.381; control2Y: 1.19; x: 2.66; y: 1.469 }
                PathLine { x: 8.721; y: 7.53 }
                PathCubic { control1X: 9.0; control1Y: 7.809; control2X: 9.0; control2Y: 8.261; x: 8.721; y: 8.54 }
                PathLine { x: 2.66; y: 14.601 }
                PathCubic { control1X: 2.381; control1Y: 14.88; control2X: 1.929; control2Y: 14.88; x: 1.65; y: 14.601 }
                PathCubic { control1X: 1.371; control1Y: 14.322; control2X: 1.371; control2Y: 13.87; x: 1.65; y: 13.591 }
                PathLine { x: 7.205; y: 8.035 }
                PathLine { x: 1.65; y: 2.479 }
                PathCubic { control1X: 1.371; control1Y: 2.2; control2X: 1.371; control2Y: 1.748; x: 1.65; y: 1.469 }
                PathLine { x: 1.65; y: 1.469 }
            }
        }
    }
    Component {
        id: arrowsShape
        Shape {
            scale: Math.min(iconRoot.width / 10, iconRoot.height / 16)
            transformOrigin: Item.TopLeft
            ShapePath {
                strokeWidth: 0
                strokeColor: "transparent"
                fillColor: iconRoot.color
                PathMove { x: 2.397; y: 4.7384 }
                PathLine { x: 4.5688; y: 2.5665 }
                PathLine { x: 5.0075; y: 2.1278 }
                PathLine { x: 5.4266; y: 2.5469 }
                PathLine { x: 7.5985; y: 4.7187 }
                PathLine { x: 8.531; y: 5.6512 }
                PathCubic { control1X: 8.8282; control1Y: 5.9485; control2X: 9.3102; control2Y: 5.9485; x: 9.6075; y: 5.6512 }
                PathCubic { control1X: 9.9047; control1Y: 5.354; control2X: 9.9047; control2Y: 4.872; x: 9.6075; y: 4.5747 }
                PathLine { x: 8.675; y: 3.6423 }
                PathLine { x: 6.5031; y: 1.4704 }
                PathLine { x: 5.5706; y: 0.5379 }
                PathCubic { control1X: 5.3595; control1Y: 0.3267; control2X: 5.0551; control2Y: 0.2656; x: 4.7899; y: 0.3544 }
                PathCubic { control1X: 4.6561; control1Y: 0.3855; control2X: 4.5291; control2Y: 0.4532; x: 4.4248; y: 0.5575 }
                PathLine { x: 3.4924; y: 1.49 }
                PathLine { x: 1.3205; y: 3.6619 }
                PathLine { x: 0.388; y: 4.5943 }
                PathCubic { control1X: 0.0907; control1Y: 4.8916; control2X: 0.0907; control2Y: 5.3736; x: 0.388; y: 5.6708 }
                PathCubic { control1X: 0.6853; control1Y: 5.9681; control2X: 1.1672; control2Y: 5.9681; x: 1.4645; y: 5.6708 }
                PathLine { x: 2.397; y: 4.7384 }
                PathLine { x: 2.397; y: 4.7384 }
                PathMove { x: 2.397; y: 11.257 }
                PathLine { x: 4.5688; y: 13.4289 }
                PathLine { x: 5.0075; y: 13.8675 }
                PathLine { x: 5.4266; y: 13.4485 }
                PathLine { x: 7.5985; y: 11.2766 }
                PathLine { x: 8.531; y: 10.3441 }
                PathCubic { control1X: 8.8282; control1Y: 10.0468; control2X: 9.3102; control2Y: 10.0468; x: 9.6075; y: 10.3441 }
                PathCubic { control1X: 9.9047; control1Y: 10.6414; control2X: 9.9047; control2Y: 11.1233; x: 9.6075; y: 11.4206 }
                PathLine { x: 8.675; y: 12.3531 }
                PathLine { x: 6.5031; y: 14.525 }
                PathLine { x: 5.5706; y: 15.4574 }
                PathCubic { control1X: 5.3594; control1Y: 15.6686; control2X: 5.0551; control2Y: 15.7298; x: 4.7899; y: 15.6409 }
                PathCubic { control1X: 4.6561; control1Y: 15.6098; control2X: 4.5291; control2Y: 15.5421; x: 4.4248; y: 15.4378 }
                PathLine { x: 3.4924; y: 14.5053 }
                PathLine { x: 1.3205; y: 12.3335 }
                PathLine { x: 0.388; y: 11.401 }
                PathCubic { control1X: 0.0907; control1Y: 11.1037; control2X: 0.0907; control2Y: 10.6217; x: 0.388; y: 10.3245 }
                PathCubic { control1X: 0.6853; control1Y: 10.0272; control2X: 1.1672; control2Y: 10.0272; x: 1.4645; y: 10.3245 }
                PathLine { x: 2.397; y: 11.257 }
                PathLine { x: 2.397; y: 11.257 }
            }
        }
    }
    Component {
        id: checkShape
        Shape {
            scale: Math.min(iconRoot.width / 56, iconRoot.height / 56)
            transformOrigin: Item.TopLeft
            ShapePath {
                strokeWidth: 0
                strokeColor: "transparent"
                fillColor: iconRoot.color
                PathMove { x: 46.8171; y: 18.1514 }
                PathCubic { control1X: 48.0496; control1Y: 16.6624; control2X: 47.8417; control2Y: 14.4561; x: 46.3527; y: 13.2235 }
                PathCubic { control1X: 44.8636; control1Y: 11.991; control2X: 42.6573; control2Y: 12.1989; x: 41.4247; y: 13.6879 }
                PathLine { x: 22.9535; y: 36.0031 }
                PathLine { x: 13.4007; y: 26.4502 }
                PathCubic { control1X: 12.0338; control1Y: 25.0833; control2X: 9.8177; control2Y: 25.0833; x: 8.4509; y: 26.4502 }
                PathCubic { control1X: 7.0841; control1Y: 27.817; control2X: 7.0841; control2Y: 30.0331; x: 8.4509; y: 31.3999 }
                PathLine { x: 20.7077; y: 43.6567 }
                PathCubic { control1X: 21.7243; control1Y: 44.6733; control2X: 23.2108; control2Y: 44.9338; x: 24.4682; y: 44.4381 }
                PathCubic { control1X: 25.0159; control1Y: 44.2302; control2X: 25.5189; control2Y: 43.8818; x: 25.9192; y: 43.3982 }
                PathLine { x: 46.8171; y: 18.1514 }
                PathLine { x: 46.8171; y: 18.1514 }
            }
        }
    }
}
