package io.github.screencamera;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs a command as the current (shell) user and returns its combined output. */
final class Shell {
    private Shell() {
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    static Result run(String... cmd) throws IOException, InterruptedException {
        Ln.d("exec: " + Arrays.toString(cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = readAll(p.getInputStream());
        int code = p.waitFor();
        Ln.d("exit=" + code + " out=" + out.trim());
        return new Result(code, out);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
