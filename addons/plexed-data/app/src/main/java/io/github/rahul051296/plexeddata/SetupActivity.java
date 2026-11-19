package io.github.rahul051296.plexeddata;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Settings over ADB, no screen of its own: the weather's place and unit (extras lat, lon, place, unit c/f, as for
 * Dashboard data), and optionally a Plex server address to use instead of the one found after linking, for a
 * server that lets the home network in without a key ({@code --es plex http://192.168.1.20:32400}; an empty value
 * goes back to the found one).
 */
public final class SetupActivity extends Activity {
    // commit(), not apply(): started over ADB and finished at once, the settings must be on disk before it exits.
    @SuppressLint("ApplySharedPref")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String plex = in.getStringExtra("plex");
        if (plex != null) {
            getSharedPreferences(PlexedProvider.PREFS, MODE_PRIVATE).edit().putString("plex", plex.trim()).commit();
            RecentWatching.fetch(getApplicationContext());
            if (in.getStringExtra("lat") == null) {
                Toast.makeText(this, plex.trim().isEmpty() ? "Plex server: found automatically" : "Plex server saved",
                        Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }
        String lat = in.getStringExtra("lat"), lon = in.getStringExtra("lon"), unit = in.getStringExtra("unit");
        boolean ok = false;
        try {
            if (lat != null && lon != null) {
                double la = Double.parseDouble(lat), lo = Double.parseDouble(lon);
                ok = la >= -90 && la <= 90 && lo >= -180 && lo <= 180;
            }
        } catch (NumberFormatException ignored) {
            ok = false;
        }
        if (ok) {
            android.content.SharedPreferences.Editor e = getSharedPreferences(WeatherProvider.PREFS, MODE_PRIVATE).edit()
                    .putString("lat", lat).putString("lon", lon)
                    // New_York reads as New York: a space is hard to pass through adb shell.
                    .putString("place", in.getStringExtra("place") == null ? "" : in.getStringExtra("place").replace('_', ' ').trim())
                    .putLong("updated", 0).putLong("tried", 0);
            // No unit given: keep the owner's earlier choice, or °F/°C by the TV's country.
            if (unit != null) e.putString("unit", "c".equalsIgnoreCase(unit) ? "c" : "f");
            e.commit();
            WeatherProvider.fetchNow(getApplicationContext());
        }
        Toast.makeText(this, ok ? "Weather place saved" : "Needs lat and lon extras", Toast.LENGTH_SHORT).show();
        finish();
    }
}
