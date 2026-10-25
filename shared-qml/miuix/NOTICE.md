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

QPlayer's optional liquid-glass phone navigation and MiniPlayer backdrop are a modified Java/QML/Skija
port of example/component/liquid/{LiquidGlassNavigationBar,Lens,Vibrancy,
InnerShadow,CombinedBackdrop}, example/component/animation/{DampedDragAnimation,
InteractiveHighlight}, and miuix-blur's shaders, blur and device-tilt code.
Copyright 2026 compose-miuix-ui contributors, Apache License 2.0.
Reference commit: b587aaf2c907c77a8c3c02d8df0aede2937ef8f0.
The upstream liquid and animation components are adapted from
Kyant0/AndroidLiquidGlass, also under Apache License 2.0:
https://github.com/Kyant0/AndroidLiquidGlass

The modified port is in components/LiquidGlassNavigation.qml and MiniPlayer.qml,
player-core's LiquidGlassNode/LiquidGlassMotion and shaders/liquid-glass,
and Android's LiquidGlassTilt. Geometry, shader formulas and spring parameters
are translated to qml4j/Skija; GPU capture, lifecycle and hidden-source layout
are adapted to QPlayer's renderer.

LiquidGlassLsq2Tracker is a modified Java port of Compose VelocityTracker1D's
quadratic least-squares strategy, Copyright 2019 The Android Open Source Project,
Apache License 2.0:
https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/pointer/util/VelocityTracker.kt
