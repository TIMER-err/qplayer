package dev.t1m3.qplayer.desktop.audio;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads AAC access units from regular or fragmented ISO BMFF files. */
final class Mp4AacTrack {
    private static final int MAX_METADATA = 32 * 1024 * 1024;
    private static final int MAX_SAMPLES = 2_000_000;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int MAX_BOXES = 100_000;

    static final class Frame {
        final byte[] data;
        final long timeMs;

        Frame(byte[] data, long timeMs) {
            this.data = data;
            this.timeMs = timeMs;
        }
    }

    private static final class Sample {
        final long offset;
        final int size;
        final long time;
        final long duration;

        Sample(long offset, int size, long time, long duration) {
            this.offset = offset;
            this.size = size;
            this.time = time;
            this.duration = duration;
        }
    }

    private static final class Defaults {
        long description = 1;
        long duration;
        long size;
    }

    private static final class Box {
        final String type;
        final ByteBuffer data;

        Box(String type, ByteBuffer data) {
            this.type = type;
            this.data = data;
        }
    }

    private static final class TopBox {
        final String type;
        final long start;
        final long body;
        final long end;

        TopBox(String type, long start, long body, long end) {
            this.type = type;
            this.start = start;
            this.body = body;
            this.end = end;
        }
    }

    private final SeekableByteSource source;
    private final List<Sample> samples = new ArrayList<>();
    private final Map<Long, Defaults> defaults = new HashMap<>();
    private byte[] config;
    private long trackId;
    private long timescale;
    private long duration;
    private long nextDecodeTime;
    private long scanPosition;
    private long descriptionIndex;
    private boolean fragmented;
    private boolean durationKnown;
    private boolean exhausted;
    private int sampleIndex;
    private int scannedBoxes;

    Mp4AacTrack(SeekableByteSource source) throws IOException {
        this.source = source;
        for (;;) {
            TopBox box = nextTopBox();
            if (box == null) throw invalid("missing movie metadata (moov)");
            if ("moov".equals(box.type)) {
                readMovie(readMetadata(box));
                break;
            }
        }
        if (fragmented && samples.isEmpty()) ensureSample(0);
        if (samples.isEmpty()) throw invalid("AAC track has no samples");
    }

    byte[] decoderConfig() {
        return config.clone();
    }

    long durationMs() {
        return durationKnown || exhausted ? toMillis(duration) : 0;
    }

    Frame nextFrame() throws IOException {
        if (!ensureSample(sampleIndex)) return null;
        Sample sample = samples.get(sampleIndex++);
        byte[] bytes = new byte[sample.size];
        source.seek(sample.offset);
        readFully(bytes, 0, bytes.length);
        return new Frame(bytes, toMillis(sample.time));
    }

    long seek(long ms) throws IOException {
        long target = Math.max(0, ms);
        // Walk future fragment headers only as far as the requested position.
        // Reads still pass through the progressive source, including after seek.
        while (fragmented && !exhausted &&
                (samples.isEmpty() || toMillis(sampleEnd(samples.get(samples.size() - 1))) <= target)) {
            if (!readNextFragment()) break;
        }
        if (samples.isEmpty()) return 0;
        int low = 0;
        int high = samples.size() - 1;
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (toMillis(samples.get(mid).time) <= target) low = mid;
            else high = mid - 1;
        }
        sampleIndex = low;
        return toMillis(samples.get(low).time);
    }

    private boolean ensureSample(int index) throws IOException {
        while (index >= samples.size() && fragmented && !exhausted) {
            if (!readNextFragment()) break;
        }
        return index < samples.size();
    }

    private boolean readNextFragment() throws IOException {
        int previous = samples.size();
        while (!exhausted) {
            TopBox box = nextTopBox();
            if (box == null) break;
            if ("moof".equals(box.type)) {
                readFragment(box, readMetadata(box));
                if (samples.size() > previous) return true;
            }
        }
        return false;
    }

    private void readMovie(ByteBuffer movie) throws IOException {
        List<Box> boxes = boxes(movie);
        Box mvex = first(boxes, "mvex");
        fragmented = mvex != null;
        if (mvex != null) {
            for (Box box : boxes(mvex.data)) {
                if (!"trex".equals(box.type)) continue;
                ByteBuffer b = box.data.duplicate();
                skip(b, 4);
                long id = u32(b);
                Defaults value = new Defaults();
                value.description = u32(b);
                value.duration = u32(b);
                value.size = u32(b);
                skip(b, 4);
                defaults.put(id, value);
            }
        }
        String rejected = "no AAC audio track";
        for (Box track : boxes) {
            if (!"trak".equals(track.type)) continue;
            Box media = child(track.data, "mdia");
            if (media == null) continue;
            Box handler = child(media.data, "hdlr");
            if (handler == null) continue;
            ByteBuffer h = handler.data.duplicate();
            skip(h, 8);
            if (!"soun".equals(fourcc(h))) continue;
            Box stbl = requiredChild(requiredChild(media.data, "minf").data, "stbl");
            Box stsd = requiredChild(stbl.data, "stsd");
            ByteBuffer descriptions = stsd.data.duplicate();
            skip(descriptions, 4);
            int count = count(descriptions, 8, 1024);
            List<Box> entries = boxes(descriptions);
            if (entries.size() != count) throw invalid("inconsistent sample descriptions");
            for (int i = 0; i < entries.size(); i++) {
                Box entry = entries.get(i);
                if ("enca".equals(entry.type)) rejected = "encrypted audio is unsupported";
                if (!"mp4a".equals(entry.type)) continue;
                byte[] decoderConfig = readAudioEntry(entry.data);
                if (decoderConfig == null) continue;
                config = decoderConfig;
                descriptionIndex = i + 1L;
                ByteBuffer tkhd = requiredChild(track.data, "tkhd").data.duplicate();
                int version = version(tkhd);
                skip(tkhd, version == 1 ? 16 : 8);
                trackId = u32(tkhd);
                ByteBuffer mdhd = requiredChild(media.data, "mdhd").data.duplicate();
                version = version(mdhd);
                skip(mdhd, version == 1 ? 16 : 8);
                timescale = u32(mdhd);
                if (timescale == 0) throw invalid("zero audio timescale");
                long declared = version == 1 ? duration64(mdhd) : u32(mdhd);
                if (declared != 0xffffffffL && declared > 0) {
                    duration = declared;
                    durationKnown = true;
                }
                if (!durationKnown && mvex != null) readExtendedDuration(boxes, mvex);
                readSampleTable(stbl.data);
                if (!samples.isEmpty()) {
                    nextDecodeTime = sampleEnd(samples.get(samples.size() - 1));
                    duration = Math.max(duration, nextDecodeTime);
                }
                if (!fragmented) exhausted = true;
                return;
            }
        }
        throw invalid(rejected);
    }

    private void readExtendedDuration(List<Box> movie, Box mvex) throws IOException {
        Box mehd = child(mvex.data, "mehd");
        Box mvhd = first(movie, "mvhd");
        if (mehd == null || mvhd == null) return;
        ByteBuffer extended = mehd.data.duplicate();
        int extendedVersion = version(extended);
        long total = extendedVersion == 1 ? duration64(extended) : u32(extended);
        if (total == 0 || total == 0xffffffffL) return;
        ByteBuffer header = mvhd.data.duplicate();
        int headerVersion = version(header);
        skip(header, headerVersion == 1 ? 16 : 8);
        long movieScale = u32(header);
        if (movieScale == 0) throw invalid("zero movie timescale");
        duration = add(multiply(total / movieScale, timescale),
                multiply(total % movieScale, timescale) / movieScale);
        durationKnown = true;
    }

    private byte[] readAudioEntry(ByteBuffer data) throws IOException {
        ByteBuffer b = data.duplicate();
        skip(b, 8); // SampleEntry reserved bytes and data-reference index.
        int soundVersion = u16(b);
        skip(b, 18);
        if (soundVersion == 1) skip(b, 16);
        else if (soundVersion != 0) throw invalid("unsupported MP4 audio sample entry version");
        Box esds = child(b, "esds");
        if (esds == null) {
            Box wave = child(b, "wave");
            if (wave != null) esds = child(wave.data, "esds");
        }
        if (esds == null) throw invalid("AAC sample entry lacks decoder configuration");
        ByteBuffer descriptors = esds.data.duplicate();
        skip(descriptors, 4);
        byte[] result = readDescriptors(descriptors, 0);
        if (result == null) throw invalid("missing AAC decoder-specific descriptor");
        return result;
    }

    private byte[] readDescriptors(ByteBuffer b, int depth) throws IOException {
        if (depth > 4) throw invalid("nested AAC descriptors");
        while (b.hasRemaining()) {
            int tag = u8(b);
            int size = 0;
            int current;
            int fields = 0;
            do {
                current = u8(b);
                size = (size << 7) | (current & 0x7f);
                if (++fields == 4 && (current & 0x80) != 0) throw invalid("invalid descriptor length");
            } while ((current & 0x80) != 0);
            ByteBuffer body = take(b, size);
            if (tag == 5) {
                if (size < 2 || size > 1024) throw invalid("invalid AAC decoder configuration");
                byte[] result = new byte[size];
                body.get(result);
                return result;
            }
            if (tag == 3) {
                skip(body, 2);
                int flags = u8(body);
                if ((flags & 0x80) != 0) skip(body, 2);
                if ((flags & 0x40) != 0) skip(body, u8(body));
                if ((flags & 0x20) != 0) skip(body, 2);
                byte[] result = readDescriptors(body, depth + 1);
                if (result != null) return result;
            } else if (tag == 4) {
                int objectType = u8(body);
                if (objectType != 0x40 && (objectType < 0x66 || objectType > 0x68)) {
                    throw invalid("MP4 audio codec is not AAC");
                }
                skip(body, 12);
                byte[] result = readDescriptors(body, depth + 1);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void readSampleTable(ByteBuffer stbl) throws IOException {
        Box stszBox = child(stbl, "stsz");
        if (stszBox == null) throw invalid("missing sample sizes (stsz)");
        ByteBuffer stsz = stszBox.data.duplicate();
        skip(stsz, 4);
        long fixedSize = u32(stsz);
        int sampleCount = count(stsz, fixedSize == 0 ? 4 : 0, MAX_SAMPLES);
        if (sampleCount == 0) return;
        int[] sizes = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) sizes[i] = frameSize(fixedSize == 0 ? u32(stsz) : fixedSize);

        Box offsetsBox = child(stbl, "stco");
        boolean wide = false;
        if (offsetsBox == null) {
            offsetsBox = child(stbl, "co64");
            wide = true;
        }
        if (offsetsBox == null) throw invalid("missing audio chunk offsets");
        ByteBuffer chunks = offsetsBox.data.duplicate();
        skip(chunks, 4);
        int chunkCount = count(chunks, wide ? 8 : 4, MAX_SAMPLES);
        long[] chunkOffsets = new long[chunkCount];
        for (int i = 0; i < chunkCount; i++) chunkOffsets[i] = wide ? u64(chunks) : u32(chunks);

        ByteBuffer stsc = requiredChild(stbl, "stsc").data.duplicate();
        skip(stsc, 4);
        int entryCount = count(stsc, 12, MAX_SAMPLES);
        if (entryCount == 0 || chunkCount == 0) throw invalid("empty audio chunk table");
        long[] firstChunk = new long[entryCount];
        long[] perChunk = new long[entryCount];
        for (int i = 0; i < entryCount; i++) {
            firstChunk[i] = u32(stsc);
            perChunk[i] = u32(stsc);
            if (u32(stsc) != descriptionIndex) throw invalid("changing audio sample descriptions is unsupported");
            if ((i == 0 && firstChunk[i] != 1) || firstChunk[i] > chunkCount ||
                    (i > 0 && firstChunk[i] <= firstChunk[i - 1]) || perChunk[i] < 1 || perChunk[i] > sampleCount) {
                throw invalid("invalid sample-to-chunk table");
            }
        }

        ByteBuffer stts = requiredChild(stbl, "stts").data.duplicate();
        skip(stts, 4);
        int timingEntries = count(stts, 8, MAX_SAMPLES);
        long[] durations = new long[sampleCount];
        int timingIndex = 0;
        for (int i = 0; i < timingEntries; i++) {
            long run = u32(stts);
            long delta = u32(stts);
            if (run > sampleCount - timingIndex || delta == 0) throw invalid("invalid sample timing table");
            Arrays.fill(durations, timingIndex, timingIndex + (int) run, delta);
            timingIndex += (int) run;
        }
        if (timingIndex != sampleCount) throw invalid("inconsistent sample timing count");
        int index = 0;
        int entry = 0;
        long time = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            while (entry + 1 < entryCount && firstChunk[entry + 1] <= chunk + 1L) entry++;
            long offset = chunkOffsets[chunk];
            if (perChunk[entry] > sampleCount - index) throw invalid("inconsistent chunk sample count");
            for (int s = 0; s < perChunk[entry]; s++) {
                addSample(offset, sizes[index], time, durations[index]);
                offset = add(offset, sizes[index]);
                time = add(time, durations[index]);
                index++;
            }
        }
        if (index != sampleCount) throw invalid("missing audio chunks");
    }

    private void readFragment(TopBox moof, ByteBuffer data) throws IOException {
        long implicitBase = moof.start;
        for (Box traf : boxes(data)) {
            if (!"traf".equals(traf.type)) continue;
            List<Box> children = boxes(traf.data);
            Box header = first(children, "tfhd");
            if (header == null) throw invalid("fragment lacks track header");
            ByteBuffer tfhd = header.data.duplicate();
            int flags = flags(tfhd);
            long id = u32(tfhd);
            Defaults inherited = defaults.get(id);
            long base = (flags & 1) != 0 ? u64(tfhd)
                    : (flags & 0x020000) != 0 ? moof.start : implicitBase;
            long description = (flags & 2) != 0 ? u32(tfhd) : inherited == null ? 1 : inherited.description;
            long defaultDuration = (flags & 8) != 0 ? u32(tfhd) : inherited == null ? 0 : inherited.duration;
            long defaultSize = (flags & 16) != 0 ? u32(tfhd) : inherited == null ? 0 : inherited.size;
            if ((flags & 32) != 0) skip(tfhd, 4);
            boolean selected = id == trackId;
            if (selected && description != descriptionIndex) throw invalid("changing audio sample descriptions is unsupported");
            if (selected && (first(children, "senc") != null || first(children, "saiz") != null)) {
                throw invalid("encrypted audio fragments are unsupported");
            }
            long time = nextDecodeTime;
            Box tfdtBox = first(children, "tfdt");
            if (tfdtBox != null) {
                ByteBuffer tfdt = tfdtBox.data.duplicate();
                int version = version(tfdt);
                time = version == 1 ? u64(tfdt) : u32(tfdt);
            }
            if (selected && !samples.isEmpty() && time < samples.get(samples.size() - 1).time) {
                throw invalid("audio fragments are out of order");
            }
            long dataEnd = base;
            for (Box run : children) {
                if (!"trun".equals(run.type)) continue;
                ByteBuffer trun = run.data.duplicate();
                int runFlags = flags(trun);
                long count = u32(trun);
                int fields = ((runFlags & 0x100) != 0 ? 4 : 0) + ((runFlags & 0x200) != 0 ? 4 : 0)
                        + ((runFlags & 0x400) != 0 ? 4 : 0) + ((runFlags & 0x800) != 0 ? 4 : 0);
                if (count > MAX_SAMPLES || (selected && count > MAX_SAMPLES - samples.size())) {
                    throw invalid("too many fragment samples");
                }
                long offset = (runFlags & 1) != 0 ? signedAdd(base, i32(trun)) : dataEnd;
                if ((runFlags & 4) != 0) skip(trun, 4);
                if (count * fields > trun.remaining()) throw invalid("truncated fragment run");
                for (int i = 0; i < count; i++) {
                    long sampleDuration = (runFlags & 0x100) != 0 ? u32(trun) : defaultDuration;
                    long sampleSize = (runFlags & 0x200) != 0 ? u32(trun) : defaultSize;
                    if ((runFlags & 0x400) != 0) skip(trun, 4);
                    if ((runFlags & 0x800) != 0) {
                        int compositionOffset = i32(trun);
                        if (selected && compositionOffset != 0) throw invalid("AAC composition offsets are unsupported");
                    }
                    if (sampleSize == 0) throw invalid("fragment sample size is missing");
                    if (selected) {
                        if (sampleDuration == 0) throw invalid("fragment sample duration is missing");
                        addSample(offset, frameSize(sampleSize), time, sampleDuration);
                        time = add(time, sampleDuration);
                    }
                    offset = add(offset, sampleSize);
                }
                dataEnd = offset;
            }
            implicitBase = dataEnd;
            if (selected) {
                nextDecodeTime = time;
                duration = Math.max(duration, time);
            }
        }
    }

    private void addSample(long offset, int size, long time, long sampleDuration) throws IOException {
        if (samples.size() >= MAX_SAMPLES) throw invalid("too many AAC samples");
        long end = add(offset, size);
        long total = source.size();
        if (total >= 0 && end > total) throw invalid("AAC sample extends past the file");
        samples.add(new Sample(offset, size, time, sampleDuration));
    }

    private TopBox nextTopBox() throws IOException {
        if (exhausted) return null;
        if (++scannedBoxes > MAX_BOXES) throw invalid("too many MP4 boxes");
        source.seek(scanPosition);
        byte[] header = new byte[8];
        int first = source.read(header, 0, header.length);
        if (first < 0) {
            long total = source.size();
            if (total >= 0 && scanPosition > total) throw invalid("truncated MP4 box");
            exhausted = true;
            return null;
        }
        readFully(header, first, header.length - first);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        long size = u32(b);
        String type = fourcc(b);
        long body = add(scanPosition, 8);
        if (size == 1) {
            readFully(header, 0, 8);
            size = u64(ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN));
            body = add(body, 8);
        }
        long end;
        if (size == 0) {
            end = source.size();
            if (end < 0) {
                if ("mdat".equals(type)) {
                    exhausted = true;
                    return new TopBox(type, scanPosition, body, -1);
                }
                throw invalid("unbounded metadata box");
            }
        } else {
            end = add(scanPosition, size);
        }
        if (end < body) throw invalid("invalid MP4 box size");
        long total = source.size();
        if (total >= 0 && end > total) throw invalid("truncated MP4 box");
        TopBox result = new TopBox(type, scanPosition, body, end);
        scanPosition = end;
        return result;
    }

    private ByteBuffer readMetadata(TopBox box) throws IOException {
        long length = box.end - box.body;
        if (length < 0 || length > MAX_METADATA) throw invalid("MP4 metadata box is too large");
        byte[] data = new byte[(int) length];
        source.seek(box.body);
        readFully(data, 0, data.length);
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    }

    private void readFully(byte[] data, int offset, int length) throws IOException {
        while (length > 0) {
            int n = source.read(data, offset, length);
            if (n < 0) throw new EOFException("Truncated MP4 data");
            if (n == 0) throw invalid("MP4 source made no progress");
            offset += n;
            length -= n;
        }
    }

    private static List<Box> boxes(ByteBuffer data) throws IOException {
        ByteBuffer b = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        List<Box> result = new ArrayList<>();
        while (b.hasRemaining()) {
            int start = b.position();
            long size = u32(b);
            String type = fourcc(b);
            if (size == 1) size = u64(b);
            else if (size == 0) size = b.limit() - start;
            long payload = size - (b.position() - start);
            if (payload < 0 || payload > b.remaining()) throw invalid("invalid " + type + " box size");
            result.add(new Box(type, take(b, (int) payload)));
            if (result.size() > MAX_BOXES) throw invalid("too many child boxes");
        }
        return result;
    }

    private static Box child(ByteBuffer data, String type) throws IOException {
        return first(boxes(data), type);
    }

    private static Box requiredChild(ByteBuffer data, String type) throws IOException {
        Box result = child(data, type);
        if (result == null) throw invalid("missing " + type + " box");
        return result;
    }

    private static Box first(List<Box> boxes, String type) {
        for (Box box : boxes) if (type.equals(box.type)) return box;
        return null;
    }

    private static int count(ByteBuffer b, int stride, int limit) throws IOException {
        long count = u32(b);
        if (count > limit || count * stride > b.remaining()) throw invalid("invalid MP4 table count");
        return (int) count;
    }

    private static int frameSize(long size) throws IOException {
        if (size < 1 || size > MAX_FRAME_BYTES) throw invalid("invalid AAC frame size");
        return (int) size;
    }

    private long toMillis(long time) {
        long seconds = time / timescale;
        if (seconds > Long.MAX_VALUE / 1000) return Long.MAX_VALUE;
        long fraction = time % timescale * 1000 / timescale;
        long whole = seconds * 1000;
        return whole > Long.MAX_VALUE - fraction ? Long.MAX_VALUE : whole + fraction;
    }

    private static long sampleEnd(Sample sample) throws IOException {
        return add(sample.time, sample.duration);
    }

    private static int version(ByteBuffer b) throws IOException {
        int v = u8(b);
        skip(b, 3);
        if (v > 1) throw invalid("unsupported MP4 box version");
        return v;
    }

    private static int flags(ByteBuffer b) throws IOException {
        int value = i32(b);
        if ((value >>> 24) > 1) throw invalid("unsupported MP4 box version");
        return value & 0xffffff;
    }

    private static int u8(ByteBuffer b) throws IOException {
        require(b, 1);
        return b.get() & 0xff;
    }

    private static int u16(ByteBuffer b) throws IOException {
        require(b, 2);
        return b.getShort() & 0xffff;
    }

    private static int i32(ByteBuffer b) throws IOException {
        require(b, 4);
        return b.getInt();
    }

    private static long u32(ByteBuffer b) throws IOException {
        return i32(b) & 0xffffffffL;
    }

    private static long u64(ByteBuffer b) throws IOException {
        require(b, 8);
        long value = b.getLong();
        if (value < 0) throw invalid("MP4 offset or timestamp exceeds signed 64-bit range");
        return value;
    }

    private static long duration64(ByteBuffer b) throws IOException {
        require(b, 8);
        long value = b.getLong();
        if (value == -1) return 0;
        if (value < 0) throw invalid("invalid MP4 duration");
        return value;
    }

    private static String fourcc(ByteBuffer b) throws IOException {
        byte[] bytes = new byte[4];
        require(b, 4);
        b.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void skip(ByteBuffer b, int length) throws IOException {
        require(b, length);
        b.position(b.position() + length);
    }

    private static ByteBuffer take(ByteBuffer b, int length) throws IOException {
        require(b, length);
        ByteBuffer slice = b.slice().order(ByteOrder.BIG_ENDIAN);
        slice.limit(length);
        b.position(b.position() + length);
        return slice;
    }

    private static void require(ByteBuffer b, int length) throws IOException {
        if (length < 0 || length > b.remaining()) throw invalid("truncated MP4 metadata");
    }

    private static long add(long a, long b) throws IOException {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw invalid("MP4 size or timestamp overflow");
        return a + b;
    }

    private static long signedAdd(long a, int b) throws IOException {
        if (b >= 0) return add(a, b);
        long result = a + b;
        if (result < 0) throw invalid("negative MP4 sample offset");
        return result;
    }

    private static long multiply(long a, long b) throws IOException {
        if (a < 0 || b < 0 || (b != 0 && a > Long.MAX_VALUE / b)) throw invalid("MP4 timestamp overflow");
        return a * b;
    }

    private static IOException invalid(String message) {
        return new IOException("MP4: " + message);
    }
}
