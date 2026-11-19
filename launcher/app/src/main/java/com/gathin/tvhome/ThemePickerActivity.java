package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

/**
 * The palette button's dropdown: every theme in the on-TV library (ThemeLibrary), plus the built-in look.
 * Picking one copies it over the active theme and closes; Home rebuilds with it in onResume.
 */
public final class ThemePickerActivity extends Activity {
    private boolean applying;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_theme_picker);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        String current = ThemeLibrary.current(this);
        View focus = null;
        for (String name : ThemeLibrary.names(this)) {
            View row = addRow(rows, ThemeLibrary.label(name), name, name.equals(current));
            if (focus == null || name.equals(current)) focus = row;
        }
        if (focus == null) {
            finish();
            return;
        }
        View target = focus;
        target.requestFocus();
        findViewById(R.id.scroll).post(() -> target.requestFocus());
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        super.onPause();
        if (!applying) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private View addRow(LinearLayout rows, String title, String name, boolean selected) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_palette);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> pick(name));
        rows.addView(row);
        return row;
    }

    private void pick(String name) {
        if (applying) return;
        applying = true;
        new Thread(() -> {
            boolean ok;
            try {
                ThemeLibrary.apply(getApplicationContext(), name);
                ok = true;
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            boolean done = ok;
            runOnUiThread(() -> {
                if (!done) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                finish();
                overridePendingTransition(0, 0);
            });
        }, "theme-apply").start();
    }
}
