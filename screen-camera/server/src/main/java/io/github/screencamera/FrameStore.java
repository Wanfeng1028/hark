package io.github.screencamera;

import android.graphics.Bitmap;

/** Holds the most recent desktop frame. Writers replace it; renderers read it. */
final class FrameStore {
    private Bitmap latest;
    private long sequence;
    private long updatedAtMs;

    synchronized void put(Bitmap bitmap) {
        // Old bitmap is not recycled here: a renderer may still be drawing it. GC reclaims it.
        latest = bitmap;
        sequence++;
        updatedAtMs = System.currentTimeMillis();
    }

    synchronized Bitmap get() {
        return latest;
    }

    synchronized long sequence() {
        return sequence;
    }

    synchronized long ageMs() {
        return latest == null ? Long.MAX_VALUE : System.currentTimeMillis() - updatedAtMs;
    }

    synchronized void clear() {
        latest = null;
        sequence++;
    }
}
