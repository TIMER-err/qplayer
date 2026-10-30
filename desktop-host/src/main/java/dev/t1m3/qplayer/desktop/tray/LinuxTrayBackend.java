package dev.t1m3.qplayer.desktop.tray;

import java.util.function.Consumer;

/** Common surface for the modern SNI tray and the AppIndicator fallback. */
abstract class LinuxTrayBackend {
    private boolean available;
    private Consumer<Boolean> availabilityListener = ignored -> { };

    /** Notifications may originate on the backend thread; consumers must marshal UI work. */
    final synchronized void setAvailabilityListener(Consumer<Boolean> listener) {
        availabilityListener = listener;
        listener.accept(available);
    }

    final synchronized boolean isAvailable() {
        return available;
    }

    protected final synchronized void setAvailable(boolean value) {
        if (available == value) return;
        available = value;
        availabilityListener.accept(value);
    }

    abstract Object addItem(String label, Runnable action);
    abstract void addSeparator();
    abstract void setLabel(Object handle, String label);
    abstract void setIconPng(byte[] png);
    abstract void setTooltip(String tip);
    void setLeftClickAction(Runnable action) { }
    abstract boolean install();
    abstract void shutdown();
}
