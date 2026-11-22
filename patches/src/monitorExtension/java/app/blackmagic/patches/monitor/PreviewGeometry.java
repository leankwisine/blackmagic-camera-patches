package app.blackmagic.patches.monitor;

import java.nio.FloatBuffer;

/** Centered geometry in viewport pixels. GL vertices use x-right, y-up, with a center origin. */
final class PreviewGeometry {
    private static final float[] STOCK_VERTICES = {-1, -1, 1, -1, -1, 1, 1, 1};

    final float drawWidth;
    final float drawHeight;
    final float clipWidth;
    final float clipHeight;
    private final int viewportWidth;
    private final int viewportHeight;
    private final PreviewSettings.Rotation rotation;

    private PreviewGeometry(float drawWidth, float drawHeight, float clipWidth, float clipHeight,
                            int viewportWidth, int viewportHeight, PreviewSettings.Rotation rotation) {
        this.drawWidth = drawWidth;
        this.drawHeight = drawHeight;
        this.clipWidth = clipWidth;
        this.clipHeight = clipHeight;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.rotation = rotation;
    }

    /** The source is the stock video presentation before the extra clockwise rotation. */
    static PreviewGeometry calculate(int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight,
                                     PreviewSettings settings) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0
            || settings.usesStockPreview()) {
            return null;
        }

        boolean swap = settings.rotation.swapsAxes();
        float width = swap ? sourceHeight : sourceWidth;
        float height = swap ? sourceWidth : sourceHeight;
        float frameWidth = viewportWidth;
        float frameHeight = viewportHeight;
        float ratio = settings.aspect.ratio;
        if (ratio > 0) {
            if (settings.scale == PreviewSettings.Scale.FILL) {
                frameWidth = Math.max(viewportWidth, viewportHeight * ratio);
                frameHeight = frameWidth / ratio;
            } else {
                frameWidth = Math.min(viewportWidth, viewportHeight * ratio);
                frameHeight = frameWidth / ratio;
            }
        }

        float scaleX = frameWidth / width;
        float scaleY = frameHeight / height;
        boolean cover = settings.scale == PreviewSettings.Scale.FILL
            || settings.scale == PreviewSettings.Scale.CENTER_CROP;
        float scale = cover ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        return new PreviewGeometry(width * scale, height * scale,
            Math.min(frameWidth, viewportWidth), Math.min(frameHeight, viewportHeight),
            viewportWidth, viewportHeight, settings.rotation);
    }

    boolean matchesViewport(long dimensions) {
        return viewportWidth == (int) (dimensions >>> 32) && viewportHeight == (int) dimensions;
    }

    /** Focus coordinates are normalized to the full view, with y increasing downwards. */
    boolean containsDisplayPoint(float x, float y) {
        return Math.abs(x - 0.5f) <= Math.min(clipWidth, drawWidth) / (2 * viewportWidth)
            && Math.abs(y - 0.5f) <= Math.min(clipHeight, drawHeight) / (2 * viewportHeight);
    }

    Point displayToStock(float x, float y) {
        if (!containsDisplayPoint(x, y)) {
            return null;
        }
        float u = (x - 0.5f) * viewportWidth / drawWidth;
        float v = (y - 0.5f) * viewportHeight / drawHeight;
        switch (rotation) {
            case CLOCKWISE_90:
                return new Point(0.5f + v, 0.5f - u);
            case CLOCKWISE_180:
                return new Point(0.5f - u, 0.5f - v);
            case CLOCKWISE_270:
                return new Point(0.5f - v, 0.5f + u);
            default:
                return new Point(0.5f + u, 0.5f + v);
        }
    }

    Point stockToDisplay(float x, float y) {
        float u = x - 0.5f;
        float v = y - 0.5f;
        float dx;
        float dy;
        switch (rotation) {
            case CLOCKWISE_90:
                dx = -v;
                dy = u;
                break;
            case CLOCKWISE_180:
                dx = -u;
                dy = -v;
                break;
            case CLOCKWISE_270:
                dx = v;
                dy = -u;
                break;
            default:
                dx = u;
                dy = v;
        }
        float displayX = 0.5f + dx * drawWidth / viewportWidth;
        float displayY = 0.5f + dy * drawHeight / viewportHeight;
        return containsDisplayPoint(displayX, displayY) ? new Point(displayX, displayY) : null;
    }

    static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    void writeVertices(FloatBuffer vertices) {
        float scaleX = drawWidth / viewportWidth;
        float scaleY = drawHeight / viewportHeight;
        vertices.clear();
        for (int index = 0; index < STOCK_VERTICES.length; index += 2) {
            float x = STOCK_VERTICES[index];
            float y = STOCK_VERTICES[index + 1];
            switch (rotation) {
                case CLOCKWISE_90:
                    vertices.put(y * scaleX).put(-x * scaleY);
                    break;
                case CLOCKWISE_180:
                    vertices.put(-x * scaleX).put(-y * scaleY);
                    break;
                case CLOCKWISE_270:
                    vertices.put(-y * scaleX).put(x * scaleY);
                    break;
                default:
                    vertices.put(x * scaleX).put(y * scaleY);
            }
        }
        vertices.flip();
    }
    /**
     * The stock vertex shader derives UVs from vertex position. Compose its stock
     * sampling matrix with the inverse of the centered vertex transformation.
     * Both matrices use column-major storage and the arrays must be distinct.
     */
    void adjustTextureMatrix(float[] stock, float[] adjusted) {
        float inverseScaleX = viewportWidth / drawWidth;
        float inverseScaleY = viewportHeight / drawHeight;
        for (int row = 0; row < 4; row++) {
            float x = stock[row];
            float y = stock[4 + row];
            switch (rotation) {
                case CLOCKWISE_90:
                    adjusted[row] = y * inverseScaleX;
                    adjusted[4 + row] = -x * inverseScaleY;
                    break;
                case CLOCKWISE_180:
                    adjusted[row] = -x * inverseScaleX;
                    adjusted[4 + row] = -y * inverseScaleY;
                    break;
                case CLOCKWISE_270:
                    adjusted[row] = -y * inverseScaleX;
                    adjusted[4 + row] = x * inverseScaleY;
                    break;
                default:
                    adjusted[row] = x * inverseScaleX;
                    adjusted[4 + row] = y * inverseScaleY;
            }
            adjusted[8 + row] = stock[8 + row];
            adjusted[12 + row] = stock[12 + row] + (x + y - adjusted[row] - adjusted[4 + row]) * 0.5f;
        }
    }
}
