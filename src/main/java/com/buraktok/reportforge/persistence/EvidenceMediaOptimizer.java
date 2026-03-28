package com.buraktok.reportforge.persistence;

import com.luciad.imageio.webp.WebPWriteParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

public final class EvidenceMediaOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceMediaOptimizer.class);
    static final String WEBP_MIME_TYPE = "image/webp";
    private static final float LOSSY_WEBP_QUALITY = 0.8f;
    private static final float JPEG_FALLBACK_QUALITY = 0.92f;

    private EvidenceMediaOptimizer() {
        // Utility class
    }

    /**
     * A record encapsulating an array of image bytes along with its associated MIME media type.
     */
    public record OptimizedEvidencePayload(String mediaType, byte[] bytes) {
    }

    public static OptimizedEvidencePayload encodeClipboardImage(BufferedImage image) throws IOException {
        if (image == null) {
            throw new IOException("Clipboard image could not be decoded.");
        }

        boolean preserveTransparency = hasTransparentPixels(image);
        byte[] webpBytes = tryEncodeWebp(image, !preserveTransparency);
        if (webpBytes != null) {
            return new OptimizedEvidencePayload(WEBP_MIME_TYPE, webpBytes);
        }

        if (!preserveTransparency) {
            byte[] jpegBytes = encodeJpeg(image);
            if (jpegBytes != null) {
                return new OptimizedEvidencePayload("image/jpeg", jpegBytes);
            }
        }

        byte[] pngBytes = encodePng(image);
        if (pngBytes == null) {
            throw new IOException("Clipboard image could not be encoded.");
        }
        return new OptimizedEvidencePayload("image/png", pngBytes);
    }

    /**
     * Loads evidence into memory and potentially encodes image files to a smaller WebP format.
     *
     * @param mediaType     the resolved media type
     * @param originalBytes the original bytes
     * @return the resulting embedded payload containing media type and bytes
     */
    public static OptimizedEvidencePayload optimizeToWebp(String mediaType, byte[] originalBytes) {
        if (originalBytes == null) {
            return new OptimizedEvidencePayload(mediaType, null);
        }
        if (!isWebpCandidate(mediaType)) {
            return new OptimizedEvidencePayload(mediaType, originalBytes);
        }

        try {
            byte[] webpBytes = encodeWebp(originalBytes, mediaType);
            if (webpBytes == null || webpBytes.length >= originalBytes.length) {
                return new OptimizedEvidencePayload(mediaType, originalBytes);
            }
            return new OptimizedEvidencePayload(WEBP_MIME_TYPE, webpBytes);
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.debug("Unable to optimize evidence payload as WebP. Falling back to the original image.", exception);
            return new OptimizedEvidencePayload(mediaType, originalBytes);
        }
    }

    /**
     * Determines whether a given MIME string is a candidate for WebP re-encoding.
     */
    private static boolean isWebpCandidate(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/png", "image/jpeg", "image/jpg", "image/bmp" -> true;
            default -> false;
        };
    }

    /**
     * Encodes raw image bytes into WebP format using available AWT writers.
     * Uses Lossy compression for JPEGs and Lossless for other formats.
     */
    private static byte[] encodeWebp(byte[] originalBytes, String mediaType) throws IOException {
        BufferedImage image;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(originalBytes)) {
            image = ImageIO.read(inputStream);
        }
        if (image == null) {
            return null;
        }

        boolean isJpeg = mediaType != null
                && (mediaType.toLowerCase(Locale.ROOT).contains("jpeg")
                || mediaType.toLowerCase(Locale.ROOT).contains("jpg"));
        return encodeWebp(image, isJpeg);
    }

    private static byte[] tryEncodeWebp(BufferedImage image, boolean lossy) {
        try {
            return encodeWebp(image, lossy);
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.debug("Unable to encode clipboard image as WebP. Falling back to another format.", exception);
            return null;
        }
    }

    private static byte[] encodeWebp(BufferedImage image, boolean lossy) throws IOException {
        var writers = ImageIO.getImageWritersByMIMEType(WEBP_MIME_TYPE);
        ImageWriter writer = writers.hasNext() ? writers.next() : null;
        if (writer == null) {
            return null;
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(outputStream)) {
            WebPWriteParam writeParam = new WebPWriteParam(writer.getLocale() == null ? Locale.getDefault() : writer.getLocale());
            String[] compressionTypes = writeParam.getCompressionTypes();
            if (compressionTypes == null || compressionTypes.length <= WebPWriteParam.LOSSLESS_COMPRESSION) {
                return null;
            }
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

            if (lossy) {
                writeParam.setCompressionType(compressionTypes[WebPWriteParam.LOSSY_COMPRESSION]);
                writeParam.setCompressionQuality(LOSSY_WEBP_QUALITY);
            } else {
                writeParam.setCompressionType(compressionTypes[WebPWriteParam.LOSSLESS_COMPRESSION]);
            }

            writeParam.setMethod(6);
            writer.setOutput(imageOutputStream);
            writer.write(null, new IIOImage(image, null, null), writeParam);
            imageOutputStream.flush();
            return outputStream.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.hasNext() ? writers.next() : null;
        if (writer == null) {
            return null;
        }

        BufferedImage rgbImage = toOpaqueRgbImage(image);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(outputStream)) {
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] compressionTypes = writeParam.getCompressionTypes();
                if (compressionTypes != null && compressionTypes.length > 0) {
                    writeParam.setCompressionType(compressionTypes[0]);
                }
                writeParam.setCompressionQuality(JPEG_FALLBACK_QUALITY);
            }
            writer.setOutput(imageOutputStream);
            writer.write(null, new IIOImage(rgbImage, null, null), writeParam);
            imageOutputStream.flush();
            return outputStream.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", outputStream)) {
                return null;
            }
            return outputStream.toByteArray();
        }
    }

    private static BufferedImage toOpaqueRgbImage(BufferedImage image) {
        BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgbImage.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgbImage;
    }

    private static boolean hasTransparentPixels(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) < 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }
}
