Vendored from https://github.com/TIMER-err/miuix-qml

Revision: df29a05 (2026-09-13).

Local integration patches: TabRow centers fitting content and bounds its background
to the tabs; Dialog animates height changes while open (240 ms by default), while
width changes apply immediately.
Button and IconButton reserve focus indication for keyboard navigation instead
of keeping a focus overlay after pointer activation.
NavigationRail labels use the full available slot so natural-size rounding does
not force an ellipsis when the translated label actually fits.
Application-specific composition otherwise lives outside this module.
