package app.blackmagic.patches.monitor;

import java.nio.FloatBuffer;
import org.junit.Test;

import static org.junit.Assert.*;

public class PreviewGeometryTest {
    private static final float TOLERANCE = 0.001f;

    private static PreviewGeometry geometry(int sw, int sh, int vw, int vh,
                                            PreviewSettings.Rotation rotation,
                                            PreviewSettings.Aspect aspect,
                                            PreviewSettings.Scale scale) {
        return PreviewGeometry.calculate(sw, sh, vw, vh, new PreviewSettings(rotation, aspect, scale));
    }

    @Test public void landscapeSourceFitsPortraitAndLandscapeViewports() {
        PreviewGeometry portrait = geometry(1920, 1080, 1080, 1920,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        assertEquals(1080, portrait.drawWidth, TOLERANCE);
        assertEquals(607.5f, portrait.drawHeight, TOLERANCE);
        PreviewGeometry landscape = geometry(1920, 1080, 1920, 1080,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        assertEquals(1920, landscape.drawWidth, TOLERANCE);
        assertEquals(1080, landscape.drawHeight, TOLERANCE);
    }

    @Test public void landscapeBufferScaledIntoPortraitSurfacePreservesAspectAfterTurn() {
        PreviewGeometry geometry = geometry(1920, 1080, 684, 1216,
            PreviewSettings.Rotation.CLOCKWISE_90, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        assertEquals(684, geometry.drawWidth, TOLERANCE);
        assertEquals(1216, geometry.drawHeight, TOLERANCE);
        PreviewGeometry unrotated = geometry(1920, 1080, 684, 1216,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        assertEquals(684, unrotated.drawWidth, TOLERANCE);
        assertEquals(384.75f, unrotated.drawHeight, TOLERANCE);
    }

    @Test public void portraitSourceFitsPortraitViewport() {
        PreviewGeometry geometry = geometry(1080, 1920, 1080, 1920,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        assertEquals(1080, geometry.drawWidth, TOLERANCE);
        assertEquals(1920, geometry.drawHeight, TOLERANCE);
    }

    @Test public void quarterTurnsSwapDimensionsAndRotateClockwise() {
        assertRotation(PreviewSettings.Rotation.CLOCKWISE_90, new float[]{-1, 1, -1, -1, 1, 1, 1, -1});
        assertRotation(PreviewSettings.Rotation.CLOCKWISE_180, new float[]{1, 1, -1, 1, 1, -1, -1, -1});
        assertRotation(PreviewSettings.Rotation.CLOCKWISE_270, new float[]{1, -1, 1, 1, -1, -1, -1, 1});
    }

    private static void assertRotation(PreviewSettings.Rotation rotation, float[] expected) {
        boolean quarterTurn = rotation != PreviewSettings.Rotation.CLOCKWISE_180;
        PreviewGeometry geometry = geometry(1920, 1080, quarterTurn ? 1080 : 1920, quarterTurn ? 1920 : 1080,
            rotation, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        FloatBuffer vertices = FloatBuffer.allocate(8);
        geometry.writeVertices(vertices);
        float[] actual = new float[8];
        vertices.get(actual);
        assertArrayEquals(expected, actual, TOLERANCE);
    }

    @Test public void fillCoversViewportWithUniformScale() {
        PreviewGeometry geometry = geometry(1920, 1080, 1080, 1920,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FILL);
        assertEquals(3413.333f, geometry.drawWidth, TOLERANCE);
        assertEquals(1920, geometry.drawHeight, TOLERANCE);
        assertEquals(1080, geometry.clipWidth, TOLERANCE);
        assertEquals(1920, geometry.clipHeight, TOLERANCE);
    }

    @Test public void explicitAspectFitsOrCropsWithinCenteredAperture() {
        PreviewGeometry fit = geometry(1920, 1080, 1920, 1080,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.SQUARE, PreviewSettings.Scale.FIT);
        assertEquals(1080, fit.clipWidth, TOLERANCE);
        assertEquals(607.5f, fit.drawHeight, TOLERANCE);
        PreviewGeometry crop = geometry(1920, 1080, 1920, 1080,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.SQUARE, PreviewSettings.Scale.CENTER_CROP);
        assertEquals(1080, crop.clipWidth, TOLERANCE);
        assertEquals(1920, crop.drawWidth, TOLERANCE);
        assertEquals(1080, crop.drawHeight, TOLERANCE);
        PreviewGeometry fill = geometry(1920, 1080, 1920, 1080,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.SQUARE, PreviewSettings.Scale.FILL);
        assertEquals(1920, fill.clipWidth, TOLERANCE);
        assertEquals(1080, fill.clipHeight, TOLERANCE);
        assertEquals(1920, fill.drawHeight, TOLERANCE);
    }

    @Test public void availableAspectRatiosUseThePostRotationFrame() {
        for (PreviewSettings.Aspect aspect : new PreviewSettings.Aspect[]{
            PreviewSettings.Aspect.WIDE, PreviewSettings.Aspect.VERTICAL, PreviewSettings.Aspect.CLASSIC,
            PreviewSettings.Aspect.SQUARE
        }) {
            PreviewGeometry geometry = geometry(1920, 1080, 1440, 1440,
                PreviewSettings.Rotation.CLOCKWISE_90, aspect, PreviewSettings.Scale.CENTER_CROP);
            assertEquals(aspect.ratio, geometry.clipWidth / geometry.clipHeight, TOLERANCE);
            assertEquals(9f / 16f, geometry.drawWidth / geometry.drawHeight, TOLERANCE);
        }
    }

    @Test public void shaderCompensationKeepsStockTextureCoordinatesAtTransformedCorners() {
        for (PreviewSettings.Rotation rotation : PreviewSettings.Rotation.values()) {
            PreviewGeometry geometry = geometry(1920, 1080, 900, 1600, rotation,
                PreviewSettings.Aspect.CLASSIC, PreviewSettings.Scale.FIT);
            // A stock sampling transform with a crop, flip, and axis swap.
            float[] stock = {0, -0.75f, 0, 0, 0.8f, 0, 0, 0, 0, 0, 1, 0, 0.1f, 0.9f, 0, 1};
            float[] adjusted = new float[16];
            geometry.adjustTextureMatrix(stock, adjusted);
            FloatBuffer vertices = FloatBuffer.allocate(8);
            geometry.writeVertices(vertices);
            for (int index = 0; index < 4; index++) {
                float u = (vertices.get() + 1) / 2;
                float v = (vertices.get() + 1) / 2;
                assertEquals(index >= 2 ? 0.9f : 0.1f,
                    adjusted[0] * u + adjusted[4] * v + adjusted[12], TOLERANCE);
                assertEquals(index % 2 == 1 ? 0.15f : 0.9f,
                    adjusted[1] * u + adjusted[5] * v + adjusted[13], TOLERANCE);
            }
        }
    }

    @Test public void focusTapAndReticleUseOppositeQuarterTurns() {
        assertFocusMapping(1920, 1080, PreviewSettings.Rotation.ZERO, 0.2f, 0.3f);
        assertFocusMapping(1080, 1920, PreviewSettings.Rotation.CLOCKWISE_90, 0.3f, 0.8f);
        assertFocusMapping(1920, 1080, PreviewSettings.Rotation.CLOCKWISE_180, 0.8f, 0.7f);
        assertFocusMapping(1080, 1920, PreviewSettings.Rotation.CLOCKWISE_270, 0.7f, 0.2f);
    }

    private static void assertFocusMapping(int width, int height, PreviewSettings.Rotation rotation,
                                           float stockX, float stockY) {
        PreviewGeometry geometry = geometry(1920, 1080, width, height,
            rotation, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        PreviewGeometry.Point target = geometry.displayToStock(0.2f, 0.3f);
        assertEquals(stockX, target.x, TOLERANCE);
        assertEquals(stockY, target.y, TOLERANCE);
        PreviewGeometry.Point reticle = geometry.stockToDisplay(stockX, stockY);
        assertEquals(0.2f, reticle.x, TOLERANCE);
        assertEquals(0.3f, reticle.y, TOLERANCE);
    }

    @Test public void focusIgnoresLetterboxingAndTheAreaOutsideACenterCrop() {
        PreviewGeometry fit = geometry(1920, 1080, 1080, 1920,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FIT);
        assertFalse(fit.containsDisplayPoint(0.5f, 0.2f));
        assertNull(fit.displayToStock(0.5f, 0.2f));
        assertEquals(0.5f, fit.displayToStock(0.5f, 0.5f).y, TOLERANCE);
        PreviewGeometry crop = geometry(1920, 1080, 1080, 1920,
            PreviewSettings.Rotation.CLOCKWISE_90, PreviewSettings.Aspect.SQUARE, PreviewSettings.Scale.CENTER_CROP);
        assertNull(crop.displayToStock(0.5f, 0.1f));
        assertNull(crop.stockToDisplay(0.05f, 0.5f));
        assertNotNull(crop.displayToStock(0.8f, 0.5f));
    }

    @Test public void fillFocusUsesOnlyTheVisibleCenterOfTheSource() {
        PreviewGeometry fill = geometry(1920, 1080, 1080, 1920,
            PreviewSettings.Rotation.ZERO, PreviewSettings.Aspect.ORIGINAL, PreviewSettings.Scale.FILL);
        assertEquals(0.341796875f, fill.displayToStock(0f, 0.2f).x, TOLERANCE);
        assertEquals(0.658203125f, fill.displayToStock(1f, 0.2f).x, TOLERANCE);
        assertNull(fill.stockToDisplay(0.1f, 0.2f));
        assertNull(fill.displayToStock(Float.NaN, 0.2f));
    }

    @Test public void invalidDimensionsAndResetUseStockRendering() {
        assertNull(PreviewGeometry.calculate(1920, 1080, 1080, 1920, PreviewSettings.DEFAULT));
        assertNull(geometry(0, 1080, 1080, 1920, PreviewSettings.Rotation.CLOCKWISE_90,
            PreviewSettings.Aspect.WIDE, PreviewSettings.Scale.FIT));
        assertNull(geometry(1920, 1080, 1080, -1, PreviewSettings.Rotation.CLOCKWISE_90,
            PreviewSettings.Aspect.WIDE, PreviewSettings.Scale.FIT));
    }

    @Test public void preferencesRoundTripAndUnreadableValuesRestoreDefaults() {
        PreviewSettings configured = new PreviewSettings(PreviewSettings.Rotation.CLOCKWISE_270,
            PreviewSettings.Aspect.CLASSIC, PreviewSettings.Scale.CENTER_CROP);
        PreviewSettings restored = PreviewSettings.decode(configured.encode());
        assertEquals(configured.rotation, restored.rotation);
        assertEquals(configured.aspect, restored.aspect);
        assertEquals(configured.scale, restored.scale);
        assertSame(PreviewSettings.DEFAULT, PreviewSettings.decode(PreviewSettings.DEFAULT.encode()));
        assertSame(PreviewSettings.DEFAULT, PreviewSettings.decode("bad"));
        assertSame(PreviewSettings.DEFAULT, PreviewSettings.decode(null));
    }
}
