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
