package io.github.screencamera;

/**
 * Wire protocol over the adb-forwarded local socket. All integers are big-endian.
 *
 * <pre>
 * client -> server
 *   handshake : "SCAM" (4 bytes) | version u8 (=1)
 *   message   : type u8 | length u32 | payload[length]
 *     type 1 FRAME_JPEG  payload = one baseline/progressive JPEG image
 *     type 2 FRAME_RGBA  payload = width u16 | height u16 | width*height*4 bytes RGBA_8888
 *     type 3 CLEAR       payload = empty (show the placeholder again)
 *
 * server -> client
 *   handshake : "SCAM" | version u8
 *   message   : type u8 | length u32 | payload[length]
 *     type 1 STREAM_STATE payload = active u8 | width u16 | height u16
 *            width/height = upright content size the camera consumer will see (0 when inactive)
 *     type 2 INFO         payload = UTF-8 text, "key=value"
 * </pre>
 */
final class Protocol {
    static final byte[] MAGIC = {'S', 'C', 'A', 'M'};
    static final int VERSION = 1;

    static final int C_FRAME_JPEG = 1;
    static final int C_FRAME_RGBA = 2;
    static final int C_CLEAR = 3;

    static final int S_STREAM_STATE = 1;
    static final int S_INFO = 2;

    /** Hard cap to avoid allocating garbage sizes on a corrupted stream (64 MiB). */
    static final int MAX_PAYLOAD = 64 * 1024 * 1024;

    private Protocol() {
    }
}
