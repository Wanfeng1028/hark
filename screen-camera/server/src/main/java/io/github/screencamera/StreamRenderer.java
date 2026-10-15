package io.github.screencamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Surface;

/**
 * Pushes frames into one virtual-camera input Surface at a fixed rate.
 *
 * <p>The virtual camera service binds this Surface to an EGL texture and, for every capture
 * request, waits for the next producer frame (AOSP frameworks/av services/camera/virtualcamera
 * README, "Life of a capture request"), so frames must keep flowing even when the desktop image
 * does not change. CTS (VirtualCameraUtils) writes to this Surface with
 * {@code Surface.lockCanvas}, which is what we use here.
 *
 * <p>Orientation: a camera with sensor orientation S delivers buffers that must be rotated S
 * degrees clockwise to look upright on the device's natural orientation. We therefore draw the
 * desktop image rotated S degrees counter-clockwise, letterboxed (aspect ratio preserved, so QR
 * codes are never stretched).
 */
final class StreamRenderer implements Runnable {
    private final int streamId;
    private final Surface surface;
    private final int width;
    private final int height;
    private final int sensorOrientation;
    private final int fps;
    private final FrameStore frames;
    private final String label;
    private volatile boolean running = true;
    private final Thread thread;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    StreamRenderer(String label, int streamId, Surface surface, int width, int height,
            int sensorOrientation, int fps, FrameStore frames) {
        this.label = label;
        this.streamId = streamId;
        this.surface = surface;
        this.width = width;
        this.height = height;
        this.sensorOrientation = sensorOrientation;
        this.fps = fps;
        this.frames = frames;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(Math.max(18, Math.min(width, height) / 18f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        thread = new Thread(this, "render-" + label + "-" + streamId);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        final long frameNs = 1_000_000_000L / fps;
        long next = System.nanoTime();
        int failures = 0;
        while (running) {
            try {
                drawOnce();
                failures = 0;
            } catch (IllegalArgumentException | IllegalStateException e) {
                // Surface abandoned (stream closed) or not lockable.
                if (++failures >= 5 || !surface.isValid()) {
                    Ln.w("Stream " + label + "/" + streamId + " stopped: " + e);
                    return;
                }
            } catch (Throwable t) {
                Ln.e("Render error on " + label + "/" + streamId, t);
                return;
            }
            next += frameNs;
            long sleepNs = next - System.nanoTime();
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    private void drawOnce() {
        Canvas canvas = surface.lockCanvas(null);
        try {
            canvas.drawColor(Color.BLACK);
            Bitmap bmp = frames.get();
            // Content rectangle in "upright" space: rotated by 90/270 swaps the axes.
            boolean swap = sensorOrientation == 90 || sensorOrientation == 270;
            float uprightW = swap ? height : width;
            float uprightH = swap ? width : height;

            canvas.save();
            canvas.translate(width / 2f, height / 2f);
            canvas.rotate(-sensorOrientation); // Canvas.rotate is clockwise-positive.
            if (bmp != null && !bmp.isRecycled()) {
                float scale = Math.min(uprightW / bmp.getWidth(), uprightH / bmp.getHeight());
                float dw = bmp.getWidth() * scale;
                float dh = bmp.getHeight() * scale;
                RectF dst = new RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f);
                canvas.drawBitmap(bmp, null, dst, bitmapPaint);
            } else {
                canvas.drawColor(Color.rgb(32, 32, 40));
                canvas.drawText("screen-camera", 0, -textPaint.getTextSize(), textPaint);
                canvas.drawText("waiting for desktop frames…", 0, textPaint.getTextSize(), textPaint);
            }
            canvas.restore();
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }
}
