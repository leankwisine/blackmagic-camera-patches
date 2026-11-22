package app.blackmagic.patches.monitor;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.SubMenu;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Loads the display LUT with the same parser Blackmagic uses for imported .cube files. */
@SuppressWarnings("deprecation")
public final class MonitorLut {
    private static final String DOCUMENT = "monitor_lut_document";
    private static final String ENABLED = "monitor_lut_enabled";
    private static final String PICKER_TAG = "blackmagic_monitor_lut_picker";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor LOADER = Executors.newSingleThreadExecutor();
    private static volatile Lut loaded;
    private static volatile boolean enabled;
    private static SharedPreferences preferences;
    private static int loadGeneration;

    private MonitorLut() {}

    static void initialize(Context context, SharedPreferences saved) {
        preferences = saved;
        enabled = saved.getBoolean(ENABLED, false);
        String document = saved.getString(DOCUMENT, null);
        if (document != null) {
            load(context, Uri.parse(document), false);
        }
    }

    static Object active() {
        Lut lut = loaded;
        return enabled && lut != null ? lut.data : null;
    }

    static void addMenu(SubMenu menu, Context context) {
        Lut lut = loaded;
        menu.add(lut == null ? "Apply to preview" : lut.name).setCheckable(true)
            .setChecked(lut != null && enabled).setEnabled(lut != null)
            .setOnMenuItemClickListener(item -> {
                setEnabled(!enabled);
                return true;
            });
        menu.add("Choose .cube file…").setOnMenuItemClickListener(item -> {
            choose(context);
            return true;
        });
        if (lut != null || preferences.contains(DOCUMENT)) {
            menu.add("Remove monitor LUT").setOnMenuItemClickListener(item -> {
                loadGeneration++;
                loaded = null;
                enabled = false;
                preferences.edit().remove(DOCUMENT).remove(ENABLED).apply();
                return true;
            });
        }
    }

    static void setEnabled(boolean value) {
        enabled = value;
        preferences.edit().putBoolean(ENABLED, value).apply();
    }

    private static void choose(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        if (context instanceof Activity) {
            FragmentManager manager = ((Activity) context).getFragmentManager();
            if (!manager.isStateSaved() && manager.findFragmentByTag(PICKER_TAG) == null) {
                manager.beginTransaction().add(new Picker(), PICKER_TAG).commit();
            }
        } else {
            Toast.makeText(context, "File picker unavailable.", Toast.LENGTH_SHORT).show();
        }
    }

    private static void load(Context context, Uri uri, boolean selected) {
        Context app = context.getApplicationContext();
        int generation = ++loadGeneration;
        LOADER.execute(() -> {
            Lut lut = read(app, uri);
            MAIN.post(() -> {
                if (generation != loadGeneration) {
                    return;
                }
                if (lut == null) {
                    if (selected || enabled) {
                        Toast.makeText(app, "Could not load the monitor LUT.", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                loaded = lut;
                if (selected) {
                    remember(app, uri);
                    setEnabled(true);
                    Toast.makeText(app, "Monitor LUT applied.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static void remember(Context app, Uri document) {
        try {
            app.getContentResolver().takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            preferences.edit().putString(DOCUMENT, document.toString()).apply();
        } catch (SecurityException ignored) {
            // The selected provider can grant access for just this session.
            preferences.edit().remove(DOCUMENT).apply();
        }
    }

    private static Lut read(Context app, Uri uri) {
        try {
            String name;
            try (Cursor cursor = app.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return null;
                }
                name = cursor.getString(0);
            }
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".cube")) {
                return null;
            }
            Object data = parseCube(app, uri);
            if (data != null) {
                return new Lut(name, data);
            }
        } catch (IOException | RuntimeException ignored) {
            // Leave the previous selection in place if the document cannot be parsed.
        }
        return null;
    }

    // The patch connects this to the version-matched Blackmagic parser.
    private static Object parseCube(Context context, Uri uri) throws IOException {
        return null;
    }

    private static final class Lut {
        final String name;
        final Object data;

        Lut(String name, Object data) {
            this.name = name;
            this.data = data;
        }
    }

    public static final class Picker extends Fragment {
        private static final int OPEN_CUBE = 1;

        @Override public void onCreate(Bundle savedState) {
            super.onCreate(savedState);
            if (savedState == null) {
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
                try {
                    startActivityForResult(open, OPEN_CUBE);
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(getActivity(), "File picker unavailable.", Toast.LENGTH_SHORT).show();
                    close();
                }
            }
        }

        @Override public void onActivityResult(int request, int result, Intent data) {
            super.onActivityResult(request, result, data);
            if (request == OPEN_CUBE) {
                if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                    load(getActivity(), data.getData(), true);
                }
                close();
            }
        }

        private void close() {
            getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
