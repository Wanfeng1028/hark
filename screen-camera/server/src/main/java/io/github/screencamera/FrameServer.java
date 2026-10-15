package io.github.screencamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Accepts one desktop client at a time on a local abstract socket and feeds decoded frames into
 * the {@link FrameStore}. Same transport pattern as scrcpy: the desktop runs
 * {@code adb forward tcp:PORT localabstract:NAME} and connects to localhost:PORT.
 */
final class FrameServer implements VirtualCameraHost.StreamListener {
    private final Options opts;
    private final FrameStore frames;
    private final Object outLock = new Object();
    private volatile DataOutputStream out;

    private volatile boolean lastActive;
    private volatile int lastW;
    private volatile int lastH;

    FrameServer(Options opts, FrameStore frames) {
        this.opts = opts;
        this.frames = frames;
    }

    /** Blocks forever, serving clients sequentially. */
    void serve() throws IOException {
        LocalServerSocket server = new LocalServerSocket(opts.socketName);
        Ln.i("Listening on localabstract:" + opts.socketName);
        Ln.event("socket", opts.socketName);
        while (true) {
            LocalSocket socket = server.accept();
            Ln.i("Desktop client connected");
            try {
                handle(socket);
            } catch (EOFException e) {
                Ln.i("Desktop client disconnected");
            } catch (IOException | RuntimeException e) {
                Ln.w("Client error: " + e);
            } finally {
                synchronized (outLock) {
                    out = null;
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // ignore
                }
                frames.clear();
            }
        }
    }

    private void handle(LocalSocket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        DataOutputStream o = new DataOutputStream(socket.getOutputStream());

        byte[] magic = new byte[4];
        in.readFully(magic);
        int version = in.readUnsignedByte();
        if (!Arrays.equals(magic, Protocol.MAGIC) || version != Protocol.VERSION) {
            throw new IOException("Bad handshake (magic/version)");
        }
        synchronized (outLock) {
            o.write(Protocol.MAGIC);
            o.writeByte(Protocol.VERSION);
            o.flush();
            out = o;
        }
        sendInfo("camera=" + opts.width + "x" + opts.height + "@" + opts.fps);
        sendState(lastActive, lastW, lastH);

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decodeOpts.inMutable = false;

        byte[] buf = new byte[0];
        long count = 0;
        long t0 = System.currentTimeMillis();
        while (true) {
            int type = in.readUnsignedByte();
            int len = in.readInt();
            if (len < 0 || len > Protocol.MAX_PAYLOAD) {
                throw new IOException("Bad payload length " + len);
            }
            if (buf.length < len) {
                buf = new byte[len];
            }
            in.readFully(buf, 0, len);
            switch (type) {
                case Protocol.C_FRAME_JPEG: {
                    Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len, decodeOpts);
                    if (bmp == null) {
                        Ln.w("Undecodable JPEG frame (" + len + " bytes)");
                    } else {
                        frames.put(bmp);
                    }
                    break;
                }
                case Protocol.C_FRAME_RGBA: {
                    if (len < 4) {
                        throw new IOException("Short RGBA frame");
                    }
                    int w = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
                    int h = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
                    if (w <= 0 || h <= 0 || len != 4 + w * h * 4) {
                        throw new IOException("RGBA frame size mismatch: " + w + "x" + h + " len=" + len);
                    }
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    // ARGB_8888 bitmaps store bytes in R,G,B,A order in memory, matching RGBA input.
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(buf, 4, w * h * 4));
                    frames.put(bmp);
                    break;
                }
                case Protocol.C_CLEAR:
                    frames.clear();
                    break;
                default:
                    Ln.w("Ignoring unknown message type " + type);
            }
            count++;
            if (opts.verbose && count % 100 == 0) {
                long dt = System.currentTimeMillis() - t0;
                Ln.d("received " + count + " frames, " + (count * 1000 / Math.max(1, dt)) + " fps avg");
            }
        }
    }

    @Override
    public void onStreamsChanged(boolean active, int width, int height) {
        lastActive = active;
        lastW = width;
        lastH = height;
        sendState(active, width, height);
    }

    private void sendState(boolean active, int w, int h) {
        byte[] p = new byte[5];
        p[0] = (byte) (active ? 1 : 0);
        p[1] = (byte) (w >> 8);
        p[2] = (byte) w;
        p[3] = (byte) (h >> 8);
        p[4] = (byte) h;
        send(Protocol.S_STREAM_STATE, p);
    }

    void sendInfo(String text) {
        send(Protocol.S_INFO, text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(int type, byte[] payload) {
        synchronized (outLock) {
            DataOutputStream o = out;
            if (o == null) {
                return;
            }
            try {
                o.writeByte(type);
                o.writeInt(payload.length);
                o.write(payload);
                o.flush();
            } catch (IOException e) {
                Ln.d("send failed: " + e);
            }
        }
    }
}
