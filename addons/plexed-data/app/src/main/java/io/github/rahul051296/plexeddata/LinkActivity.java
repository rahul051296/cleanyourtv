package io.github.rahul051296.plexeddata;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Links the owner's Plex account once, the way Plex's own TV apps do: a short code shown here, entered at
 * plex.tv/link on a phone. The launcher opens this from "Who's watching?" while nothing is linked.
 */
public final class LinkActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView code, note;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xF0101014);
        box.addView(text("Link your Plex account", 30, Color.WHITE, true));
        box.addView(text("On your phone, open plex.tv/link and enter this code", 18, 0xB3FFFFFF, false));
        code = text("····", 64, Color.WHITE, true);
        code.setLetterSpacing(0.3f);
        code.setPadding(0, 40, 0, 40);
        box.addView(code);
        note = text("Waiting for the code to be entered…", 16, 0x8CFFFFFF, false);
        box.addView(note);
        setContentView(box);
        new Thread(() -> {
            try {
                String[] pin = PlexAccount.newPin(this);
                main.post(() -> code.setText(pin[1]));
                poll();
            } catch (Exception e) {
                main.post(() -> note.setText(R.string.link_unreachable));
            }
        }).start();
    }

    private void poll() {
        main.postDelayed(() -> new Thread(() -> {
            if (done || isFinishing()) return;
            try {
                if (PlexAccount.checkPin(this)) {
                    done = true;
                    main.post(() -> {
                        note.setText(R.string.link_done);
                        main.postDelayed(this::finish, 1500);
                    });
                    getContentResolver().notifyChange(PlexedProvider.PROFILES_URI, null);
                    RecentWatching.fetch(getApplicationContext());   // finds the server, then its lists
                    return;
                }
            } catch (Exception ignored) {
                // not entered yet, or a network hiccup: keep waiting
            }
            poll();
        }).start(), 3000);
    }

    @Override
    protected void onDestroy() {
        done = true;
        super.onDestroy();
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
}
