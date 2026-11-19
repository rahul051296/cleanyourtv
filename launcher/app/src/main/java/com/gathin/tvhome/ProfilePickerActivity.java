package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * "Who's watching?" (recent_shelf): the Plex Home profiles the data add-on knows, under the profile button.
 * Picking one asks the add-on to follow that profile (its PIN first when it has one); Recently watched then
 * reloads with that person's Continue Watching. With no Plex account linked yet, it offers the add-on's
 * plex.tv/link screen instead. The launcher never sees a Plex key: only names, avatars and the selection.
 */
public final class ProfilePickerActivity extends Activity {
    static final String EXTRA_AUTHORITY = "authority";
    private String authority;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        authority = getIntent().getStringExtra(EXTRA_AUTHORITY);
        if (authority == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_theme_picker);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        View focus = null;
        try (Cursor c = getContentResolver().query(Uri.parse("content://" + authority + "/profiles"), null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(c.getColumnIndexOrThrow("linked")) == 0) {
                    focus = addRow(rows, getString(R.string.link_plex), null, false, v -> openLink());
                    break;
                }
                String uuid = c.getString(c.getColumnIndexOrThrow("uuid"));
                String name = c.getString(c.getColumnIndexOrThrow("name"));
                boolean pin = c.getInt(c.getColumnIndexOrThrow("pin")) == 1;
                boolean selected = c.getInt(c.getColumnIndexOrThrow("selected")) == 1;
                Bitmap avatar = c.getInt(c.getColumnIndexOrThrow("avatar")) == 1 ? avatar(uuid) : null;
                View row = addRow(rows, name, avatar, selected, v -> pick(uuid, pin));
                if (focus == null || selected) focus = row;
            }
        } catch (Exception e) {
            // The add-on is missing or busy.
        }
        if (focus == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // The theme picker's panel has a fixed-height list for many themes; a few profiles need only their rows.
        View scroll = findViewById(R.id.scroll);
        scroll.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.requestLayout();
        focus.requestFocus();
    }

    private View addRow(LinearLayout rows, String title, Bitmap avatar, boolean selected, View.OnClickListener click) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        if (avatar != null) {
            icon.setImageBitmap(avatar);
            icon.setImageTintList(null);
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        } else {
            icon.setImageResource(R.drawable.ic_profile);
        }
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(click);
        rows.addView(row);
        return row;
    }

    private Bitmap avatar(String uuid) {
        try (InputStream in = getContentResolver().openInputStream(
                Uri.parse("content://" + authority + "/avatar/" + uuid.replaceAll("[^A-Za-z0-9_-]", "") + ".png"))) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private void pick(String uuid, boolean pin) {
        if (!pin) {
            // No PIN to check: close at once, and the row takes over while the switch completes.
            select(uuid, null);
            finish();
            return;
        }
        EditText entry = new EditText(this);
        entry.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_pin)
                .setView(entry)
                .setPositiveButton(android.R.string.ok, (d, w) -> select(uuid, entry.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Asks the add-on to follow the profile (it talks to plex.tv, so off the main thread). */
    private void select(String uuid, String pin) {
        new Thread(() -> {
            Bundle extras = new Bundle();
            if (pin != null) extras.putString("pin", pin);
            Bundle result = null;
            try {
                result = getContentResolver().call(Uri.parse("content://" + authority), "select", uuid, extras);
            } catch (Exception ignored) {
                // fall through to the message below
            }
            String error = result == null ? "error" : result.getString("error");
            android.content.Context app = getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (error == null) {
                    if (!isFinishing()) finish();
                } else {
                    Toast.makeText(app, "pin".equals(error) ? R.string.profile_pin_wrong : R.string.not_available,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** The add-on's plex.tv/link screen (its package is the provider's). */
    private void openLink() {
        try {
            String pkg = getPackageManager().resolveContentProvider(authority, 0).packageName;
            startActivity(new Intent().setComponent(new ComponentName(pkg, pkg + ".LinkActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
