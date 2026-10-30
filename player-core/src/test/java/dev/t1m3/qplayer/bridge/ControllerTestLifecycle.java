package dev.t1m3.qplayer.bridge;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Bounded retirement, reporting cleanup failure alongside (not instead of) the test failure. */
public final class ControllerTestLifecycle implements TestRule {
    private final List<Throwable> cleanupFailures = new ArrayList<>();

    public void close(PlayerController controller) {
        if (controller == null) return;
        try {
            controller.shutdownAsync().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cleanupFailures.add(error);
        } catch (ExecutionException error) {
            cleanupFailures.add(error.getCause() != null ? error.getCause() : error);
        } catch (java.util.concurrent.TimeoutException error) {
            cleanupFailures.add(new AssertionError("controller retirement did not finish within 10 seconds", error));
        } catch (Throwable error) {
            cleanupFailures.add(error);
        }
    }

    @Override public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                List<Throwable> failures = new ArrayList<>();
                cleanupFailures.clear();
                try { base.evaluate(); }
                catch (Throwable error) { failures.add(error); }
                failures.addAll(cleanupFailures);
                MultipleFailureException.assertEmpty(failures);
            }
        };
    }

    /** The caller has already completed its controlled transfer and any file writes. */
    static void releaseCompletedCache(AudioCacheCoordinator coordinator) throws Exception {
        coordinator.stopAdmission().get(3, TimeUnit.SECONDS);
        coordinator.finishCancellation(java.util.concurrent.CompletableFuture.completedFuture(null))
                .get(3, TimeUnit.SECONDS);
    }
}
