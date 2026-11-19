package io.github.rahul051296.plexeddata;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The weather's place when the owner has not set one: the main city of the TV's time zone, worked out on the
 * TV with no lookup. Close enough for the weather; the owner can set the exact place with SetupActivity.
 */
final class TimeZoneCities {
    /** A place to fetch the weather for, and whether it came from the time zone rather than the owner. */
    static final class Place {
        final String lat, lon, name;
        final boolean fromTimeZone;

        Place(String lat, String lon, String name, boolean fromTimeZone) {
            this.lat = lat;
            this.lon = lon;
            this.name = name;
            this.fromTimeZone = fromTimeZone;
        }
    }

    private static final Map<String, String[]> ZONES = new HashMap<>();

    private static void z(String zone, String name, String lat, String lon) {
        ZONES.put(zone, new String[] {name, lat, lon});
    }

    static {
        // Americas
        z("America/New_York", "New York", "40.71", "-74.01");
        z("America/Detroit", "Detroit", "42.33", "-83.05");
        z("America/Toronto", "Toronto", "43.65", "-79.38");
        z("America/Chicago", "Chicago", "41.88", "-87.63");
        z("America/Winnipeg", "Winnipeg", "49.90", "-97.14");
        z("America/Mexico_City", "Mexico City", "19.43", "-99.13");
        z("America/Denver", "Denver", "39.74", "-104.99");
        z("America/Edmonton", "Edmonton", "53.55", "-113.49");
        z("America/Phoenix", "Phoenix", "33.45", "-112.07");
        z("America/Los_Angeles", "Los Angeles", "34.05", "-118.24");
        z("America/Vancouver", "Vancouver", "49.28", "-123.12");
        z("America/Anchorage", "Anchorage", "61.22", "-149.90");
        z("Pacific/Honolulu", "Honolulu", "21.31", "-157.86");
        z("America/Halifax", "Halifax", "44.65", "-63.58");
        z("America/St_Johns", "St. John's", "47.56", "-52.71");
        z("America/Puerto_Rico", "San Juan", "18.47", "-66.11");
        z("America/Bogota", "Bogotá", "4.71", "-74.07");
        z("America/Lima", "Lima", "-12.05", "-77.04");
        z("America/Caracas", "Caracas", "10.48", "-66.90");
        z("America/Santiago", "Santiago", "-33.45", "-70.67");
        z("America/Argentina/Buenos_Aires", "Buenos Aires", "-34.60", "-58.38");
        z("America/Sao_Paulo", "São Paulo", "-23.55", "-46.63");
        z("America/Montevideo", "Montevideo", "-34.90", "-56.16");
        // Europe
        z("Europe/London", "London", "51.51", "-0.13");
        z("Europe/Dublin", "Dublin", "53.35", "-6.26");
        z("Europe/Lisbon", "Lisbon", "38.72", "-9.14");
        z("Europe/Madrid", "Madrid", "40.42", "-3.70");
        z("Europe/Paris", "Paris", "48.86", "2.35");
        z("Europe/Brussels", "Brussels", "50.85", "4.35");
        z("Europe/Amsterdam", "Amsterdam", "52.37", "4.90");
        z("Europe/Berlin", "Berlin", "52.52", "13.40");
        z("Europe/Zurich", "Zurich", "47.38", "8.54");
        z("Europe/Rome", "Rome", "41.90", "12.50");
        z("Europe/Vienna", "Vienna", "48.21", "16.37");
        z("Europe/Prague", "Prague", "50.08", "14.44");
        z("Europe/Warsaw", "Warsaw", "52.23", "21.01");
        z("Europe/Copenhagen", "Copenhagen", "55.68", "12.57");
        z("Europe/Stockholm", "Stockholm", "59.33", "18.07");
        z("Europe/Oslo", "Oslo", "59.91", "10.75");
        z("Europe/Helsinki", "Helsinki", "60.17", "24.94");
        z("Europe/Athens", "Athens", "37.98", "23.73");
        z("Europe/Bucharest", "Bucharest", "44.43", "26.10");
        z("Europe/Budapest", "Budapest", "47.50", "19.04");
        z("Europe/Istanbul", "Istanbul", "41.01", "28.98");
        z("Europe/Kyiv", "Kyiv", "50.45", "30.52");
        z("Europe/Kiev", "Kyiv", "50.45", "30.52");
        z("Europe/Moscow", "Moscow", "55.76", "37.62");
        // Africa and the Middle East
        z("Africa/Cairo", "Cairo", "30.04", "31.24");
        z("Africa/Lagos", "Lagos", "6.52", "3.38");
        z("Africa/Nairobi", "Nairobi", "-1.29", "36.82");
        z("Africa/Johannesburg", "Johannesburg", "-26.20", "28.05");
        z("Africa/Casablanca", "Casablanca", "33.57", "-7.59");
        z("Asia/Jerusalem", "Jerusalem", "31.77", "35.21");
        z("Asia/Riyadh", "Riyadh", "24.71", "46.68");
        z("Asia/Dubai", "Dubai", "25.20", "55.27");
        z("Asia/Tehran", "Tehran", "35.69", "51.39");
        // Asia and Oceania
        z("Asia/Karachi", "Karachi", "24.86", "67.01");
        z("Asia/Kolkata", "New Delhi", "28.61", "77.21");
        z("Asia/Calcutta", "New Delhi", "28.61", "77.21");
        z("Asia/Kathmandu", "Kathmandu", "27.72", "85.32");
        z("Asia/Dhaka", "Dhaka", "23.81", "90.41");
        z("Asia/Bangkok", "Bangkok", "13.76", "100.50");
        z("Asia/Ho_Chi_Minh", "Ho Chi Minh City", "10.82", "106.63");
        z("Asia/Jakarta", "Jakarta", "-6.21", "106.85");
        z("Asia/Kuala_Lumpur", "Kuala Lumpur", "3.14", "101.69");
        z("Asia/Singapore", "Singapore", "1.35", "103.82");
        z("Asia/Manila", "Manila", "14.60", "120.98");
        z("Asia/Shanghai", "Shanghai", "31.23", "121.47");
        z("Asia/Hong_Kong", "Hong Kong", "22.32", "114.17");
        z("Asia/Taipei", "Taipei", "25.03", "121.57");
        z("Asia/Seoul", "Seoul", "37.57", "126.98");
        z("Asia/Tokyo", "Tokyo", "35.68", "139.69");
        z("Australia/Perth", "Perth", "-31.95", "115.86");
        z("Australia/Adelaide", "Adelaide", "-34.93", "138.60");
        z("Australia/Brisbane", "Brisbane", "-27.47", "153.03");
        z("Australia/Sydney", "Sydney", "-33.87", "151.21");
        z("Australia/Melbourne", "Melbourne", "-37.81", "144.96");
        z("Pacific/Auckland", "Auckland", "-36.85", "174.76");
    }

    private TimeZoneCities() {}

    /** The main city of the time zone {@code zone}, or null when the table does not know it. */
    static Place forZone(String zone) {
        String[] c = zone == null ? null : ZONES.get(zone);
        return c == null ? null : new Place(c[1], c[2], c[0], true);
    }

    /** °F where the country uses it, °C everywhere else. */
    static String defaultUnit() {
        String country = Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        switch (country) {
            case "US": case "LR": case "MM": case "BS": case "BZ": case "KY": case "PW": case "FM": case "MH":
                return "f";
            default:
                return "c";
        }
    }
}
