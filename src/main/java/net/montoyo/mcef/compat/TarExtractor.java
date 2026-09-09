package net.montoyo.mcef.compat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Minimal read-only ustar / GNU tar extractor (tar.gz).
 *
 * Supports what the MCEF native archives actually contain:
 *  - 512-byte headers with octal size / checksum validation
 *  - regular files ('0' or NUL), directories ('5')
 *  - GNU long names ('L') and long link targets ('K') skipped
 *  - pax extended headers ('x'/'g') skipped (their payload records may carry
 *    the real name; archives produced by GNU tar only use them for exotic
 *    metadata, the ustar prefix field still carries the path we need)
 *  - ustar prefix field (name = prefix + '/' + name)
 *
 * Deliberately dependency-free: this addon must not add gradle dependencies.
 */
public class TarExtractor {

    private static final int BLOCK = 512;

    private final Path destDir;

    public TarExtractor(Path destDir) {
        this.destDir = destDir;
    }

    public void extractTarGz(Path archive) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive), 65536)) {
            extract(in);
        }
    }

    public void extract(InputStream in) throws IOException {
        byte[] hdr = new byte[BLOCK];

        // GNU long name carried over from a previous 'L' entry.
        String pendingLongName = null;

        while(true) {
            if(!readFully(in, hdr))
                break; // EOF

            boolean allZero = true;
            for(int i = 0; i < BLOCK; i++) {
                if(hdr[i] != 0) {
                    allZero = false;
                    break;
                }
            }

            if(allZero)
                continue; // end-of-archive padding or single zero block

            String name = parseString(hdr, 0, 100);
            long size = parseOctal(hdr, 124, 12);
            byte typeFlag = hdr[156];
            String prefix = parseString(hdr, 345, 155);
            int checksum = (int) parseOctal(hdr, 148, 8);

            if(!verifyChecksum(hdr, checksum))
                throw new IOException("Corrupted tar header (bad checksum) for entry: " + name);

            if(prefix.length() > 0)
                name = prefix + "/" + name;

            // Pax extended headers may override path via " path=<value>\n" records.
            if(typeFlag == 'x' || typeFlag == 'X' || typeFlag == 'g') {
                byte[] data = new byte[(int) size];
                if(!readFully(in, data))
                    throw new IOException("Truncated pax header entry");

                if(typeFlag == 'x') {
                    String override = paxPathValue(data);
                    if(override != null)
                        pendingLongName = override;
                }
                continue;
            }

            if(typeFlag == 'L') { // GNU long name
                byte[] data = new byte[(int) size];
                if(!readFully(in, data))
                    throw new IOException("Truncated GNU long name entry");

                pendingLongName = parseCString(data);
                continue;
            }

            if(typeFlag == 'K') { // GNU long link name: irrelevant for us, skip payload
                skipFully(in, size);
                continue;
            }

            if(pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }

            if(typeFlag == '5') { // directory
                secureMkdir(name);
                continue;
            }
            if(typeFlag == '0' || typeFlag == 0 || typeFlag == '7') { // regular file (7 = contiguous, treat as file)
                Path out = secureOutput(name);
                try (OutputStream os = Files.newOutputStream(out)) {
                    copyExactly(in, os, size);
                }
                continue;
            }

            // Symlinks (2), hard links (1), devices, fifos: skip payload if any.
            skipFully(in, size);
        }
    }

    private static String parseString(byte[] buf, int off, int len) {
        // Trim trailing NULs and spaces (size fields are space-padded).
        int end = off + len;
        int last = end;
        while(last > off && (buf[last - 1] == 0 || buf[last - 1] == ' '))
            last--;

        return new String(buf, off, last - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String parseCString(byte[] data) {
        int end = data.length;
        for(int i = 0; i < data.length; i++) {
            if(data[i] == 0 || data[i] == '\n') {
                end = i;
                break;
            }
        }
        return new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        // May be all NULs (size 0 expressed as zeros), or base-256 (high bit set).
        if(len > 0 && (buf[off] & 0x80) != 0) {
            // GNU base-256 encoding
            long v = buf[off] & 0x7F;
            for(int i = 1; i < len; i++)
                v = (v << 8) | (buf[off + i] & 0xFF);
            return v;
        }

        long v = 0;
        int i = off;
        int end = off + len;
        while(i < end && (buf[i] == ' ' || buf[i] == 0))
            i++;

        for(; i < end; i++) {
            byte b = buf[i];
            if(b == 0 || b == ' ')
                break;
            if(b < '0' || b > '7')
                throw new NumberFormatException("Invalid octal byte in tar header: " + b);
            v = (v << 3) | (b - '0');
        }

        return v;
    }

    private static boolean verifyChecksum(byte[] hdr, int stored) {
        int sum = 0;
        for(int i = 0; i < BLOCK; i++)
            sum += (i >= 148 && i < 156) ? 32 : (hdr[i] & 0xFF);

        return sum == stored;
    }

    private static String paxPathValue(byte[] data) {
        // Records look like: "%d %s=%s\n" e.g. "42 path=very/long/name\n"
        int i = 0;
        while(i < data.length) {
            int start = i;
            while(i < data.length && data[i] != ' ')
                i++;
            if(i >= data.length)
                break;
            int recordLen;
            try {
                recordLen = Integer.parseInt(new String(data, start, i - start, java.nio.charset.StandardCharsets.US_ASCII));
            } catch(NumberFormatException nfe) {
                return null;
            }

            int keyStart = i + 1;
            int eq = -1;
            for(int j = keyStart; j < start + recordLen && j < data.length; j++) {
                if(data[j] == '=') {
                    eq = j;
                    break;
                }
            }
            if(eq >= 0) {
                String key = new String(data, keyStart, eq - keyStart, java.nio.charset.StandardCharsets.US_ASCII);
                if(key.equals("path")) {
                    int valEnd = start + recordLen;
                    if(valEnd > 0 && data[valEnd - 1] == '\n')
                        valEnd--;
                    return new String(data, eq + 1, valEnd - (eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }

            i = start + recordLen;
        }

        return null;
    }

    private Path secureResolve(String name) throws IOException {
        Path resolved = destDir.resolve(name).normalize();
        if(!resolved.startsWith(destDir))
            throw new IOException("Tar entry escapes destination directory: " + name);
        return resolved;
    }

    private void secureMkdir(String dir) throws IOException {
        Path resolved = secureResolve(dir);
        Files.createDirectories(resolved);
    }

    private Path secureOutput(String name) throws IOException {
        Path resolved = secureResolve(name);
        Path parent = resolved.getParent();
        if(parent != null)
            Files.createDirectories(parent);
        return resolved;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while(off < buf.length) {
            int rd = in.read(buf, off, buf.length - off);
            if(rd < 0)
                return off > 0 ? false : false; // EOF mid-entry or clean EOF
            off += rd;
        }
        return true;
    }

    private static void copyExactly(InputStream in, OutputStream os, long size) throws IOException {
        byte[] buf = new byte[65536];
        long left = size;
        while(left > 0) {
            int want = (int) Math.min(buf.length, left);
            int rd = in.read(buf, 0, want);
            if(rd < 0)
                throw new IOException("Truncated tar entry payload");
            os.write(buf, 0, rd);
            left -= rd;
        }
    }

    private static void skipFully(InputStream in, long size) throws IOException {
        long left = size;
        while(left > 0) {
            long sk = in.skip(left);
            if(sk <= 0) {
                if(in.read() < 0)
                    throw new IOException("Truncated tar entry payload");
                left--;
            } else {
                left -= sk;
            }
        }
    }

}
