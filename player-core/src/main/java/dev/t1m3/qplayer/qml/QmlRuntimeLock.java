package dev.t1m3.qplayer.qml;

/**
 * Serializes qml4j property mutation against {@code DirtyQueue.flush()}.
 *
 * <p>Dirty queues are thread-local, but {@code Property.evaluating} is not.
 * A {@code Property.set} on the GLFW/Android main thread therefore reevaluates
 * dependents immediately, and that reenter races a render-thread flush of the
 * same property: qml4j reports {@code cycle detected in binding evaluation}
 * and the desktop render thread dies. Hold this monitor around every flush
 * and around host-side writes that are not already on that render thread.
 */
public final class QmlRuntimeLock {

    public static final Object MONITOR = new Object();

    private QmlRuntimeLock() {}
}
