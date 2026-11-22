package app.blackmagic.patches.monitor;

final class PreviewSettings {
    enum Rotation {
        ZERO("0°"), CLOCKWISE_90("90°"), CLOCKWISE_180("180°"), CLOCKWISE_270("270°");

        final String label;

        Rotation(String label) {
            this.label = label;
        }

        boolean swapsAxes() {
            return this == CLOCKWISE_90 || this == CLOCKWISE_270;
        }
    }

    enum Aspect {
        ORIGINAL("Original", 0), WIDE("16:9", 16f / 9), VERTICAL("9:16", 9f / 16),
        CLASSIC("4:3", 4f / 3), SQUARE("1:1", 1);

        final String label;
        final float ratio;

        Aspect(String label, float ratio) {
            this.label = label;
            this.ratio = ratio;
        }
    }

    enum Scale {
        STOCK("Default"), FIT("Fit"), FILL("Fill"), CENTER_CROP("Center crop");

        final String label;

        Scale(String label) {
            this.label = label;
        }
    }

    static final PreviewSettings DEFAULT = new PreviewSettings(Rotation.ZERO, Aspect.ORIGINAL, Scale.STOCK);

    final Rotation rotation;
    final Aspect aspect;
    final Scale scale;

    PreviewSettings(Rotation rotation, Aspect aspect, Scale scale) {
        this.rotation = rotation;
        this.aspect = aspect;
        this.scale = scale;
    }

    boolean usesStockPreview() {
        return rotation == Rotation.ZERO && aspect == Aspect.ORIGINAL && scale == Scale.STOCK;
    }

    String encode() {
        return rotation.name() + "/" + aspect.name() + "/" + scale.name();
    }

    static PreviewSettings decode(String saved) {
        if (saved == null) {
            return DEFAULT;
        }
        String[] fields = saved.split("/", -1);
        if (fields.length != 3) {
            return DEFAULT;
        }
        try {
            PreviewSettings settings = new PreviewSettings(
                Rotation.valueOf(fields[0]), Aspect.valueOf(fields[1]), Scale.valueOf(fields[2]));
            return settings.usesStockPreview() ? DEFAULT : settings;
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
