package io.github.rahul051296.dashboarddata;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Saves the place and unit from the intent's extras (lat, lon, unit c/f), then fetches once. No UI of its own. */
public final class SetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String tickers = in.getStringExtra("tickers");
        if (tickers != null) {
            getSharedPreferences(WeatherProvider.PREFS, MODE_PRIVATE).edit().putString("tickers", tickers)
                    .putLong("stocks_updated", 0).putLong("stocks_tried", 0).commit();
            WeatherProvider.fetchStocks(getApplicationContext());
            if (in.getStringExtra("lat") == null) {
                Toast.makeText(this, "Tickers saved", Toast.LENGTH_SHORT).show();
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
