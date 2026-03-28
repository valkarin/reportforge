package com.buraktok.reportforge.persistence;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceMediaOptimizerTest {

    @Test
    void encodeClipboardImageUsesCompactEncodingForOpaqueImages() throws Exception {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = (x * 37 + y * 11) & 0xFF;
                int green = (x * 19 + y * 53) & 0xFF;
                int blue = (x * 73 + y * 29) & 0xFF;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }

        byte[] pngBytes = writePng(image);
        EvidenceMediaOptimizer.OptimizedEvidencePayload payload = EvidenceMediaOptimizer.encodeClipboardImage(image);

        assertAll(
                () -> assertNotNull(payload.bytes()),
                () -> assertTrue(payload.bytes().length > 0),
                () -> assertTrue(payload.bytes().length < pngBytes.length),
                () -> assertTrue(payload.mediaType().equals("image/webp") || payload.mediaType().equals("image/jpeg"))
        );
    }

    @Test
    void encodeClipboardImagePreservesTransparency() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = x < 16 ? 0x00 : 0x80;
                int rgb = (alpha << 24) | 0x00AA5500;
                image.setRGB(x, y, rgb);
            }
        }

        EvidenceMediaOptimizer.OptimizedEvidencePayload payload = EvidenceMediaOptimizer.encodeClipboardImage(image);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(payload.bytes()));

        assertAll(
                () -> assertNotNull(decoded),
                () -> assertFalse(payload.mediaType().equals("image/jpeg") || payload.mediaType().equals("image/jpg")),
                () -> assertTrue(((decoded.getRGB(0, 0) >>> 24) & 0xFF) < 0xFF),
                () -> assertTrue(((decoded.getRGB(20, 20) >>> 24) & 0xFF) < 0xFF)
        );
    }

    private static byte[] writePng(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", outputStream));
            return outputStream.toByteArray();
        }
    }
}
