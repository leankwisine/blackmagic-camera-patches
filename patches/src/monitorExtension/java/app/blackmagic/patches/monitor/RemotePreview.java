package app.blackmagic.patches.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Size;
import android.view.Gravity;
import android.view.Menu;
import android.widget.PopupMenu;
import android.view.SubMenu;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Entry points called from the stock main-thread surface callbacks and GL output draw. */
public final class RemotePreview {
    private static final String PREFERENCES = "blackmagic_remote_preview_patch";
    private static final String PREFERENCE_KEY = "display_transform";
    private static final Set<SurfaceView> REGISTERED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<SurfaceHolder, WeakReference<SurfaceView>> VIEWS = new WeakHashMap<>();
    private static final Map<SurfaceHolder, Binding> BINDINGS = new WeakHashMap<>();
    private static final Map<Surface, Viewport> REMOTE_OUTPUTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<DrawState> DRAW_STATE = new ThreadLocal<>();
    private static final float[] BLACK = {0, 0, 0, 1};
    private static volatile PreviewSettings settings = PreviewSettings.DEFAULT;
    private static SharedPreferences preferences;

    private RemotePreview() {}

    public static void register(SurfaceView view, UUID camera) {
        SurfaceHolder holder = view.getHolder();
        if (REGISTERED.add(view)) {
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder created) {
                    prepare(view, created);
                    bind(created, camera);
                }

                @Override public void surfaceChanged(SurfaceHolder changed, int format, int width, int height) {}

                @Override public void surfaceDestroyed(SurfaceHolder destroyed) {
                    unbind(destroyed);
                }
            });
        }
        if (view.isAttachedToWindow() && holder.getSurface().isValid()) {
            prepare(view, holder);
            bind(holder, camera);
        }
    }

    public static void prepare(SurfaceView view, SurfaceHolder holder) {
        VIEWS.put(holder, new WeakReference<>(view));
    }

    public static void bind(SurfaceHolder holder, UUID camera) {
        WeakReference<SurfaceView> reference = VIEWS.remove(holder);
        SurfaceView view = reference == null ? null : reference.get();
        if (camera == null || view == null || !(view.getRootView() instanceof FrameLayout)) {
            return;
        }
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        Binding existing = BINDINGS.get(holder);
        if (existing != null && existing.surface == surface && camera.equals(existing.viewport.camera)) {
            return;
        }
        release(holder);

        Context context = view.getContext();
        if (preferences == null) {
            preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            try {
                settings = PreviewSettings.decode(preferences.getString(PREFERENCE_KEY, null));
            } catch (ClassCastException ignored) {
                settings = PreviewSettings.DEFAULT;
            }
        }

        Binding binding = new Binding(view, surface, (FrameLayout) view.getRootView(), camera);
        BINDINGS.put(holder, binding);
        REMOTE_OUTPUTS.put(surface, binding.viewport);
    }

    public static void unbind(SurfaceHolder holder) {
        VIEWS.remove(holder);
        release(holder);
    }

    private static void release(SurfaceHolder holder) {
        Binding binding = BINDINGS.remove(holder);
        if (binding != null) {
            binding.viewport.dimensions = 0;
            REMOTE_OUTPUTS.remove(binding.surface, binding.viewport);
            binding.view.removeOnLayoutChangeListener(binding);
            binding.hideButton();
            binding.parent.removeView(binding.button);
        }
    }

    private static void save(PreviewSettings updated) {
        settings = updated;
        preferences.edit().putString(PREFERENCE_KEY, updated.encode()).apply();
    }

    private static PreviewGeometry focusGeometry(UUID camera) {
        if (camera == null || settings.usesStockPreview()) {
            return null;
        }
        PreviewGeometry result = null;
        long largestArea = 0;
        for (Viewport viewport : REMOTE_OUTPUTS.values()) {
            if (!camera.equals(viewport.camera)) {
                continue;
            }
            PreviewGeometry geometry = viewport.geometry;
            long dimensions = viewport.dimensions;
            long area = (dimensions >>> 32) * (int) dimensions;
            if (area > largestArea && geometry != null && geometry.matchesViewport(dimensions)) {
                result = geometry;
                largestArea = area;
            }
        }
        return result;
    }

    public static PointF keepVideoTap(UUID camera, PointF point) {
        PreviewGeometry geometry = focusGeometry(camera);
        return geometry == null || geometry.containsDisplayPoint(point.x, point.y) ? point : null;
    }

    public static PointF focusToStock(UUID camera, PointF point) {
        PreviewGeometry geometry = focusGeometry(camera);
        if (geometry == null || point == null) {
            return point;
        }
        PreviewGeometry.Point stock = geometry.displayToStock(point.x, point.y);
        return stock == null ? point : new PointF(stock.x, stock.y);
    }

    public static PointF focusToDisplay(UUID camera, PointF point) {
        PreviewGeometry geometry = focusGeometry(camera);
        if (geometry == null || point == null) {
            return point;
        }
        PreviewGeometry.Point display = geometry.stockToDisplay(point.x, point.y);
        return display == null ? null : new PointF(display.x, display.y);
    }

    private static final class Viewport {
        final UUID camera;
        volatile PreviewGeometry geometry;

        Viewport(UUID camera) {
            this.camera = camera;
        }
        // Main thread publishes both effective View dimensions in a single write.
        volatile long dimensions;
    }

    private static final class Binding implements View.OnLayoutChangeListener {
        final SurfaceView view;
        final Surface surface;
        final FrameLayout parent;
        final Viewport viewport;
        final TextView button;
        final Runnable fade;
        final int[] viewLocation = new int[2];
        final int[] parentLocation = new int[2];
        PopupMenu popup;

        Binding(SurfaceView view, Surface surface, FrameLayout parent, UUID camera) {
            viewport = new Viewport(camera);
            this.view = view;
            this.surface = surface;
            this.parent = parent;
            Context context = parent.getContext();
            button = new TextView(context);
            button.setText("↻");
            button.setTextColor(0xFFFFFFFF);
            button.setTextSize(24);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(0xB0000000);
            button.setContentDescription("Preview transforms");
            button.setFocusable(true);
            button.setOnClickListener(anchor -> showMenu());
            button.setOnFocusChangeListener((anchor, focused) -> {
                if (focused) {
                    revealButton();
                }
            });
            fade = () -> {
                button.setBackgroundColor(0x00000000);
                button.setAlpha(0.75f);
            };
            int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT);
            parent.addView(button, layout);
            view.addOnLayoutChangeListener(this);
            updateSize(view.getWidth(), view.getHeight());
            revealButton();
        }

        private void updateSize(int width, int height) {
            viewport.dimensions = ((long) Math.max(width, 0) << 32) | Math.max(height, 0);
            view.getLocationInWindow(viewLocation);
            parent.getLocationInWindow(parentLocation);
            button.setTranslationX(viewLocation[0] - parentLocation[0] + width - button.getLayoutParams().width);
            button.setTranslationY(viewLocation[1] - parentLocation[1]);
        }

        @Override
        public void onLayoutChange(View changed, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            updateSize(right - left, bottom - top);
        }

        void revealButton() {
            button.removeCallbacks(fade);
            button.setAlpha(1);
            button.setBackgroundColor(0xB0000000);
            AccessibilityManager accessibility = button.getContext().getSystemService(AccessibilityManager.class);
            if (accessibility == null || !accessibility.isTouchExplorationEnabled()) {
                button.postDelayed(fade, 3500);
            }
        }

        void hideButton() {
            if (popup != null) {
                popup.dismiss();
            }
            button.removeCallbacks(fade);
        }

        private void showMenu() {
            revealButton();
            button.removeCallbacks(fade);
            PreviewSettings current = settings;
            PopupMenu menu = new PopupMenu(button.getContext(), button);
            popup = menu;
            Menu main = menu.getMenu();
            SubMenu rotation = main.addSubMenu("Rotation");
            for (PreviewSettings.Rotation value : PreviewSettings.Rotation.values()) {
                rotation.add(1, Menu.NONE, Menu.NONE, value.label).setCheckable(true)
                    .setChecked(value == current.rotation)
                    .setOnMenuItemClickListener(item -> {
                        save(new PreviewSettings(value, settings.aspect, settings.scale));
                        return true;
                    });
            }
            SubMenu aspect = main.addSubMenu("Aspect ratio");
            for (PreviewSettings.Aspect value : PreviewSettings.Aspect.values()) {
                aspect.add(2, Menu.NONE, Menu.NONE, value.label).setCheckable(true)
                    .setChecked(value == current.aspect)
                    .setOnMenuItemClickListener(item -> {
                        save(new PreviewSettings(settings.rotation, value, settings.scale));
                        return true;
                    });
            }
            SubMenu scale = main.addSubMenu("Fit / Fill");
            for (PreviewSettings.Scale value : PreviewSettings.Scale.values()) {
                scale.add(3, Menu.NONE, Menu.NONE, value.label).setCheckable(true)
                    .setChecked(value == current.scale)
                    .setOnMenuItemClickListener(item -> {
                        save(new PreviewSettings(settings.rotation, settings.aspect, value));
                        return true;
                    });
            }
            main.add("Reset to default").setOnMenuItemClickListener(item -> {
                save(PreviewSettings.DEFAULT);
                return true;
            });
            menu.setOnDismissListener(dismissed -> {
                popup = null;
                revealButton();
            });
            menu.show();
        }
    }

    private static final class DrawState {
        final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        final float[] matrix = new float[16];
        final int[] scissor = new int[4];
        PreviewSettings settings;
        long dimensions;
        PreviewGeometry geometry;
        PreviewGeometry pending;
        Viewport viewport;
        Size bufferSize;
        int vertexLocation;
        int matrixLocation;
        float[] stockMatrix;
    }

    public static void begin(Surface surface, Size bufferSize, int vertexLocation,
                             int matrixLocation, float[] stockMatrix) {
        DrawState state = DRAW_STATE.get();
        if (state != null) {
            state.pending = null;
        }
        PreviewSettings current = settings;
        if (current.usesStockPreview() || surface == null || vertexLocation < 0 || matrixLocation < 0) {
            return;
        }
        Viewport viewport = REMOTE_OUTPUTS.get(surface);
        if (viewport == null || bufferSize == null) {
            return;
        }
        long dimensions = viewport.dimensions;
        if (dimensions == 0 || bufferSize.getWidth() <= 0 || bufferSize.getHeight() <= 0) {
            return;
        }
        if (state == null) {
            state = new DrawState();
            DRAW_STATE.set(state);
        }
        if (state.settings != current || state.dimensions != dimensions || !bufferSize.equals(state.bufferSize)) {
            state.settings = current;
            state.dimensions = dimensions;
            int width = (int) (dimensions >>> 32);
            int height = (int) dimensions;
            // The SurfaceView can stretch the EGL buffer to a differently shaped Compose slot.
            state.geometry = PreviewGeometry.calculate(bufferSize.getWidth(), bufferSize.getHeight(),
                width, height, current);
            if (state.geometry != null) {
                state.geometry.writeVertices(state.vertices);
            }
        }
        state.pending = state.geometry;
        state.viewport = viewport;
        state.bufferSize = bufferSize;
        state.vertexLocation = vertexLocation;
        state.matrixLocation = matrixLocation;
        state.stockMatrix = stockMatrix;
    }

    public static void draw(int mode, int first, int count) {
        DrawState state = DRAW_STATE.get();
        PreviewGeometry geometry = state == null ? null : state.pending;
        if (geometry == null) {
            GLES20.glDrawArrays(mode, first, count);
            return;
        }
        state.pending = null;
        int viewWidth = (int) (state.dimensions >>> 32);
        int viewHeight = (int) state.dimensions;
        int bufferWidth = state.bufferSize.getWidth();
        int bufferHeight = state.bufferSize.getHeight();
        int clipWidth = Math.min(bufferWidth, Math.max(1, Math.round(bufferWidth * geometry.clipWidth / viewWidth)));
        int clipHeight = Math.min(bufferHeight, Math.max(1, Math.round(bufferHeight * geometry.clipHeight / viewHeight)));
        boolean clip = clipWidth < bufferWidth || clipHeight < bufferHeight;
        boolean hadScissor = clip && GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        if (clip || geometry.drawWidth < viewWidth || geometry.drawHeight < viewHeight) {
            GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, BLACK, 0);
        }
        if (clip) {
            int left = (bufferWidth - clipWidth) / 2;
            int bottom = (bufferHeight - clipHeight) / 2;
            if (hadScissor) {
                GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, state.scissor, 0);
                int right = Math.min(left + clipWidth, state.scissor[0] + state.scissor[2]);
                int top = Math.min(bottom + clipHeight, state.scissor[1] + state.scissor[3]);
                left = Math.max(left, state.scissor[0]);
                bottom = Math.max(bottom, state.scissor[1]);
                clipWidth = Math.max(0, right - left);
                clipHeight = Math.max(0, top - bottom);
            } else {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            }
            GLES20.glScissor(left, bottom, clipWidth, clipHeight);
        }
        try {
            geometry.adjustTextureMatrix(state.stockMatrix, state.matrix);
            state.vertices.position(0);
            GLES20.glVertexAttribPointer(state.vertexLocation, 2, GLES20.GL_FLOAT, false, 8, state.vertices);
            GLES20.glUniformMatrix4fv(state.matrixLocation, 1, false, state.matrix, 0);
            GLES20.glDrawArrays(mode, first, count);
            if (state.viewport.geometry != geometry) {
                state.viewport.geometry = geometry;
            }
        } finally {
            if (clip && hadScissor) {
                GLES20.glScissor(state.scissor[0], state.scissor[1], state.scissor[2], state.scissor[3]);
            } else if (clip) {
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            }
        }
    }
}
