package org.quarkpress.media.runtime;

import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.imaging.quicktime.QuickTimeMetadataReader;
import com.drew.imaging.riff.RiffHandler;
import com.drew.imaging.riff.RiffReader;
import com.drew.lang.Rational;
import com.drew.lang.StreamReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.avi.AviDirectory;
import com.drew.metadata.avi.AviRiffHandler;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.wav.WavDirectory;
import com.drew.metadata.wav.WavRiffHandler;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The metadata of an audio or video attachment: the file's size and type, and the duration and video dimensions the
 * container states, for the containers metadata-extractor reads them from (MP4, M4A and QuickTime, AVI, WAV). Other
 * containers (MP3, Ogg, WebM, FLAC and the rest) get the size and the type only. No tags, bit rates or codecs are read.
 *
 * <p>metadata-extractor allocates the size a box or chunk declares before reading it, so it never sees a size larger
 * than the file holds: an MP4 or QuickTime file reaches it as a checked copy of its movie box ({@link IsoBoxes}), and
 * an AVI or WAV file through a reader that refuses to read past the file's end.
 */
public final class AvMetadata {

    private static final Logger LOG = Logger.getLogger(AvMetadata.class);

    private static final int ONE_HOUR = 3600;

    private AvMetadata() {}

    /**
     * {@code filesize}, {@code mime_type} ({@code mimeType}, the attachment's type), then, each only when the
     * container states it, {@code length} (whole seconds, rounded), {@code length_formatted} ({@code m:ss}, or
     * {@code h:mm:ss} from one hour on), {@code width} and {@code height} (of the video track). A file the readers do
     * not understand, or that is damaged, gets the first two only.
     */
    public static ObjectNode read(Path file, String mimeType) throws IOException {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("filesize", Files.size(file));
        metadata.put("mime_type", mimeType);
        Container container;
        try {
            container = container(file);
        } catch (IOException | RuntimeException | StackOverflowError | OutOfMemoryError failure) {
            LOG.debugf("the audio or video metadata of %s could not be read: %s", file, failure);
            return metadata;
        }
        container.seconds().ifPresent(seconds -> {
            long length = Dimensions.round(seconds);
            if (seconds > 0 && length <= Integer.MAX_VALUE) {
                metadata.put("length", (int) length);
                metadata.put("length_formatted", formatted(length));
            }
        });
        if (container.width().isPresent() && container.height().isPresent()) {
            metadata.put("width", container.width().getAsInt());
            metadata.put("height", container.height().getAsInt());
        }
        return metadata;
    }

    /** What a container states: its duration in seconds and its video dimensions. */
    record Container(OptionalDouble seconds, OptionalInt width, OptionalInt height) {

        static final Container NOTHING =
                new Container(OptionalDouble.empty(), OptionalInt.empty(), OptionalInt.empty());
    }

    private static Container container(Path file) throws IOException {
        FileType type;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            type = FileTypeDetector.detectFileType(in);
        }
        return switch (type) {
            case Mp4 ->
                IsoBoxes.movie(file)
                        .map(movie -> isoMedia(Mp4MetadataReader.readMetadata(new ByteArrayInputStream(movie))))
                        .orElse(Container.NOTHING);
            case QuickTime ->
                IsoBoxes.movie(file)
                        .map(movie -> isoMedia(QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(movie))))
                        .orElse(Container.NOTHING);
            case Avi -> riff(file, AviRiffHandler::new).map(AvMetadata::avi).orElse(Container.NOTHING);
            case Wav -> riff(file, WavRiffHandler::new).map(AvMetadata::wav).orElse(Container.NOTHING);
            default -> Container.NOTHING;
        };
    }

    /** The metadata a RIFF handler reads from {@code file}; empty when the file is no RIFF file after all. */
    private static Optional<Metadata> riff(Path file, Function<Metadata, RiffHandler> handler) throws IOException {
        Metadata metadata = new Metadata();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            new RiffReader().processRiff(new BoundedReader(in, Files.size(file)), handler.apply(metadata));
        } catch (com.drew.imaging.riff.RiffProcessingException damaged) {
            return Optional.empty();
        }
        return Optional.of(metadata);
    }

    /** A stream reader that refuses to read, or to allocate for, more bytes than remain in a file of known length. */
    private static final class BoundedReader extends StreamReader {

        private final long length;

        BoundedReader(InputStream in, long length) {
            super(in);
            this.length = length;
        }

        @Override
        public byte[] getBytes(int count) throws IOException {
            if (count < 0 || count > length - getPosition()) {
                throw new EOFException("a chunk declares more bytes than the file holds");
            }
            return super.getBytes(count);
        }
    }

    /** MP4 and QuickTime files: the movie header's duration, the video track's sample description dimensions. */
    private static Container isoMedia(Metadata metadata) {
        OptionalDouble seconds = OptionalDouble.empty();
        for (Directory movie : metadata.getDirectoriesOfType(Mp4Directory.class)) {
            seconds = seconds.isPresent() ? seconds : rational(movie, Mp4Directory.TAG_DURATION_SECONDS);
        }
        for (Directory movie : metadata.getDirectoriesOfType(QuickTimeDirectory.class)) {
            seconds = seconds.isPresent() ? seconds : rational(movie, QuickTimeDirectory.TAG_DURATION_SECONDS);
        }
        OptionalInt width = OptionalInt.empty();
        OptionalInt height = OptionalInt.empty();
        for (Directory video : metadata.getDirectoriesOfType(Mp4VideoDirectory.class)) {
            if (width.isEmpty()) {
                width = positive(video, Mp4VideoDirectory.TAG_WIDTH);
                height = positive(video, Mp4VideoDirectory.TAG_HEIGHT);
            }
        }
        for (Directory video : metadata.getDirectoriesOfType(QuickTimeVideoDirectory.class)) {
            if (width.isEmpty()) {
                width = positive(video, QuickTimeVideoDirectory.TAG_WIDTH);
                height = positive(video, QuickTimeVideoDirectory.TAG_HEIGHT);
            }
        }
        return new Container(seconds, width, height);
    }

    private static Container avi(Metadata metadata) {
        AviDirectory avi = metadata.getFirstDirectoryOfType(AviDirectory.class);
        if (avi == null) {
            return Container.NOTHING;
        }
        return new Container(
                clock(avi.getString(AviDirectory.TAG_DURATION)),
                positive(avi, AviDirectory.TAG_WIDTH),
                positive(avi, AviDirectory.TAG_HEIGHT));
    }

    private static Container wav(Metadata metadata) {
        WavDirectory wav = metadata.getFirstDirectoryOfType(WavDirectory.class);
        if (wav == null) {
            return Container.NOTHING;
        }
        return new Container(clock(wav.getString(WavDirectory.TAG_DURATION)), OptionalInt.empty(), OptionalInt.empty());
    }

    private static OptionalDouble rational(Directory directory, int tag) {
        Object value = directory.getObject(tag);
        if (value instanceof Rational rational && rational.getDenominator() != 0) {
            double seconds = rational.doubleValue();
            return Double.isFinite(seconds) && seconds >= 0 ? OptionalDouble.of(seconds) : OptionalDouble.empty();
        }
        return OptionalDouble.empty();
    }

    private static OptionalInt positive(Directory directory, int tag) {
        Object value = directory.getObject(tag);
        if (value instanceof Number number && number.longValue() > 0 && number.longValue() <= Integer.MAX_VALUE) {
            return OptionalInt.of(number.intValue());
        }
        return OptionalInt.empty();
    }

    /**
     * The seconds of the {@code hh:mm:ss} text metadata-extractor gives for AVI and WAV durations (already rounded to
     * whole seconds). From one hour on, metadata-extractor writes the minutes within the hour but the seconds past
     * those minutes counted from the start ({@code 01:01:3640} for 3700 seconds); such a seconds field (60 or more)
     * is read accordingly.
     */
    static OptionalDouble clock(String text) {
        if (text == null) {
            return OptionalDouble.empty();
        }
        long[] fields = new long[3];
        int field = 0;
        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (digits >= 12) {
                    return OptionalDouble.empty();
                }
                fields[field] = fields[field] * 10 + (c - '0');
                digits++;
            } else if (c == ':' && digits > 0 && field < 2) {
                field++;
                digits = 0;
            } else {
                return OptionalDouble.empty();
            }
        }
        if (field != 2 || digits == 0) {
            return OptionalDouble.empty();
        }
        long hours = fields[0];
        long minutes = fields[1];
        long seconds = fields[2];
        long total = seconds >= 60 ? seconds + minutes * 60 : hours * ONE_HOUR + minutes * 60 + seconds;
        return OptionalDouble.of(total);
    }

    /** {@code m:ss} below one hour, {@code h:mm:ss} from one hour on. */
    static String formatted(long seconds) {
        long hours = seconds / ONE_HOUR;
        long minutes = (seconds % ONE_HOUR) / 60;
        long rest = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append(':');
            if (minutes < 10) {
                text.append('0');
            }
        }
        text.append(minutes).append(':');
        if (rest < 10) {
            text.append('0');
        }
        return text.append(rest).toString();
    }
}
