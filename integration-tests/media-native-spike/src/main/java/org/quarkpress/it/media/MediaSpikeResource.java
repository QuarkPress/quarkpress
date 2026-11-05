package org.quarkpress.it.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.ParseContext;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Spike endpoints: the tests post image bytes and check what comes back, in JVM mode and natively. */
@Path("/media")
public class MediaSpikeResource {

    /** Created per resource instance, so at run time in a native executable, never in the image heap. */
    private final Detector detector = new DefaultDetector();

    /** The registered ImageIO reader providers, to see which plugins the native image found. */
    @GET
    @Path("/readers")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> readers() {
        List<String> names = new ArrayList<>();
        Iterator<ImageReaderSpi> providers =
                IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, true);
        providers.forEachRemaining(spi -> names.add(spi.getClass().getName()));
        return names;
    }

    @POST
    @Path("/inspect")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    public InspectResult inspect(byte[] bytes) throws Exception {
        String mimeType;
        try (TikaInputStream in = TikaInputStream.get(bytes)) {
            mimeType = detector.detect(in, new org.apache.tika.metadata.Metadata(), new ParseContext())
                    .toString();
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("no ImageIO reader for " + mimeType);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, false);
                BufferedImage image = reader.read(0);
                Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
                ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
                IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
                JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
                return new InspectResult(
                        mimeType,
                        reader.getFormatName(),
                        reader.getClass().getName(),
                        image.getWidth(),
                        image.getHeight(),
                        jpeg == null ? null : jpeg.getInteger(JpegDirectory.TAG_NUMBER_OF_COMPONENTS),
                        String.format("%06x", image.getRGB(0, 0) & 0xFFFFFF),
                        exif == null ? null : exif.getInteger(ExifIFD0Directory.TAG_ORIENTATION),
                        iptc == null ? null : iptc.getString(IptcDirectory.TAG_CAPTION));
            } finally {
                reader.dispose();
            }
        }
    }

    /** Scales the posted image to {@code width x height} with Graphics2D and writes it as {@code format}. */
    @POST
    @Path("/resize")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public byte[] resize(
            byte[] bytes,
            @QueryParam("format") String format,
            @QueryParam("width") int width,
            @QueryParam("height") int height)
            throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            throw new IOException("ImageIO could not read the upload");
        }
        int type = "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        var g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawRenderedImage(
                    source,
                    AffineTransform.getScaleInstance(
                            (double) width / source.getWidth(), (double) height / source.getHeight()));
        } finally {
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IOException("no ImageIO writer for " + format);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("jpeg".equals(format)) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.82f); // WordPress's default quality, 82
            }
            writer.write(null, new IIOImage(target, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Failures come back as text with the whole cause chain, so a native failure is readable in the CI log. */
    @ServerExceptionMapper
    public Response failure(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null && text.length() < 8000; t = t.getCause()) {
            text.append(t.getClass().getName())
                    .append(": ")
                    .append(t.getMessage())
                    .append('\n');
            for (StackTraceElement frame : t.getStackTrace()) {
                text.append("  at ").append(frame).append('\n');
                if (text.length() > 8000) {
                    break;
                }
            }
        }
        return Response.serverError()
                .type(MediaType.TEXT_PLAIN)
                .entity(text.toString())
                .build();
    }
}
