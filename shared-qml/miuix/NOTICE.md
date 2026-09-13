# MIUIX QML

Visual design and default color tokens are ported from compose-miuix-ui/miuix
(Apache-2.0):

- https://github.com/compose-miuix-ui/miuix

Public QML API matches `md3.Core` (`Button`, `Switch`, `Theme.color.primary`, …)
so an app can swap `import md3.Core` for `import miuix.Core`.

The basic Search, Check, ArrowRight, and ArrowUpDown vector paths in
miuix/Core/Icon.qml are adapted from compose-miuix-ui/miuix:
Copyright 2025 compose-miuix-ui contributors, Apache License 2.0.
Reference commit: 5157b503e86e2bfc2db61db00fff5df41326394a.

The continuous-corner geometry in miuix/Core/SmoothRectangle.qml is adapted
from miuix-squircle/SquirclePath.kt, Copyright 2026 compose-miuix-ui
contributors, Apache License 2.0. Dialog, dropdown, and flat indication
metrics follow the same reference commit noted above.

The RadioButton check path and TextField chrome metrics are adapted from
basic/RadioButton.kt and basic/TextField.kt, Copyright 2025 compose-miuix-ui
contributors, Apache License 2.0, at the same reference commit.

NavigationBar and TabRow geometry, colors, and display-state behavior follow
basic/NavigationBar.kt and basic/TabRow.kt from the same upstream revision.

NavigationRail geometry follows basic/NavigationRail.kt (Copyright 2025),
and badge metrics/bounds follow basic/Badge.kt (Copyright 2026), by
compose-miuix-ui contributors, Apache License 2.0.

TopAppBar, FloatingActionButton, FloatingToolbar, Snackbar, Tooltip, progress
indicators, NumberPicker and PullToRefresh metrics follow the same reference.
MonetScheme.qml adapts theme/MonetMapping.kt; ColorMath.qml adapts the color
matrices and OkHSV transforms from color/core/Transforms.kt, Copyright 2025
compose-miuix-ui contributors, Apache License 2.0. The OkHSV gamut boundary
is solved numerically instead of using the reference polynomial approximation.
