Vendored from https://github.com/TIMER-err/miuix-qml

Revision: ad7dc8c (2026-09-13).

QPlayer's shared component
fixes for tabs, dialog height transitions, pointer focus, and navigation labels
have been incorporated upstream. Application-specific composition lives outside
this module.

Local compatibility patch: Dialog content widths bind directly to the viewport,
so qml4j v0.2.32 positioner sizing cannot pin text to the icon width.

Local compatibility patch: ColorPicker computes a moved channel's color directly
before emitting it, because qml4j defers derived binding updates until its dirty
queue flushes; the assigned color and colorSelected payload must match.

Local compatibility patch: Dialog overlays expose consumeBack to the application
root, so host-dispatched Back/Escape obeys the same closeOnEscape policy as keys
handled inside the overlay, including page-local dialogs reparented to the root.

Local compatibility patch: SuperDropdown releases its hidden overlay's focus.
Keyboard selection restores focus only to a visible anchor; hidden anchors cannot
open a menu. Space is consumed inside the open popup. Its root-reparented overlay
exposes consumeBack for QPlayer's host-dispatched Back/Escape.

Local compatibility patch: Menu's root-reparented overlay exposes consumeBack,
so host-dispatched Back/Escape closes an open menu instead of navigating the
page underneath.

Local style patch: Menu uses 44 px items with an inset rounded hover/press
highlight, 20 px secondary-color icons, 15 px labels, a chevron for submenus,
6 px padding, an 18 px outlined surface and a 0.92 -> 1 entrance scale.
