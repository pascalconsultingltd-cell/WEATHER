package app.pascal.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared by the home-screen widgets: fetches the conditions right now, remembers them and draws the widget faces.
 * Same location and rules as the chart: wind arrows get a double head from ARROW2 km/h gusts and a triple head from ARROW3.
 */
final class Weather {
    static final double LAT = 51.553, LON = -0.142;
    static final String TZ = "Europe%2FLondon";
    static final int ARROW2 = 30, ARROW3 = 45;
    static final String API = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
            + "&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_gusts_10m,wind_direction_10m"
            + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&wind_speed_unit=kmh&timezone=" + TZ;
    // latest Hampstead Heath Lido water temperature, saved to the repo by a scheduled job (published about weekly)
    static final String LIDO_URL = "https://raw.githubusercontent.com/pascalconsultingltd-cell/WEATHER/main/lido.json";
    static final String PREFS = "weather";

    // widget faces are drawn on a 100x100 grid scaled up to SIZE pixels
    static final int SIZE = 300;
    static final float K = SIZE / 100f;
    static final int WHITE = 0xFFFFFFFF, MUTED = 0xFFDCE6EC, GREEN = 0xFF3DDC84, GOLD = 0xFFFFC21A, SHADOW = 0xB0000000;

    private Weather() {}

    /** Fetches fresh values off the main thread, stores them and redraws every widget. */
    static void refresh(final Context context, final BroadcastReceiver.PendingResult pending) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject j = new JSONObject(get(API));
                    JSONObject cur = j.getJSONObject("current"), day = j.getJSONObject("daily");
                    int speed = (int) Math.round(cur.getDouble("wind_speed_10m"));
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean("ok", true)
                            .putInt("speed", speed)
                            .putInt("gust", Math.max(speed, (int) Math.round(cur.optDouble("wind_gusts_10m", speed))))
                            .putFloat("dir", (float) cur.optDouble("wind_direction_10m", 0))
                            .putInt("temp", (int) Math.round(cur.getDouble("temperature_2m")))
                            .putInt("tmax", (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)))
                            .putInt("tmin", (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)))
                            .putInt("code", cur.optInt("weather_code", 3))
                            .putBoolean("day", cur.optInt("is_day", 1) == 1)
                            .apply();
                } catch (Exception e) {
                    // offline or the service is down: the widgets keep showing the last values
                }
                try {
                    JSONObject lido = new JSONObject(get(LIDO_URL));
                    if (!lido.isNull("lido")) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putInt("lido", lido.getInt("lido"))
                                .putString("lidoDate", lido.optString("date", ""))
                                .apply();
                    }
                } catch (Exception e) {
                    // same: keep the last published reading
                }
                try {
                    render(context);
                } finally {
                    if (pending != null) pending.finish();
                }
            }
        }).start();
    }

    static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Redraws all widgets of both kinds from the stored values. */
    static void render(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean ok = p.getBoolean("ok", false);
        show(context, WindWidget.class, ok ? drawWind(p.getInt("speed", 0), p.getInt("gust", 0), p.getFloat("dir", 0)) : drawEmpty());
        show(context, TempWidget.class, ok ? drawTemp(p.getInt("code", 3), p.getBoolean("day", true), p.getInt("temp", 0), p.getInt("tmax", 0), p.getInt("tmin", 0)) : drawEmpty());
        show(context, LidoWidget.class, p.contains("lido") ? drawLido(p.getInt("lido", 0), p.getString("lidoDate", "")) : drawEmpty());
    }

    private static void show(Context context, Class<?> provider, Bitmap face) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
        if (ids.length == 0) return;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_tile);
        views.setImageViewBitmap(R.id.img, face);
        // tapping a widget opens the chart
        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(ids, views);
    }

    /** Text paint with a soft dark halo so it stays readable on any wallpaper (the widgets have no background). */
    private static Paint text(float size, int colour, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        p.setTextSize(size * K);
        p.setColor(colour);
        p.setShadowLayer(2.2f * K, 0, 0.6f * K, SHADOW);
        return p;
    }

    private static Bitmap blank() {
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    }

    /** Shown until the first forecast arrives. */
    static Bitmap drawEmpty() {
        Bitmap bmp = blank();
        Paint t = text(30, MUTED, true);
        t.setTextAlign(Paint.Align.CENTER);
        new Canvas(bmp).drawText("…", 50 * K, 58 * K, t);
        return bmp;
    }

    /** Arrow pointing where the wind blows TO (bearing it comes from + 180), with "wind-gust" km/h underneath. */
    static Bitmap drawWind(int speed, int gust, float dir) {
        Bitmap bmp = blank();
        Canvas c = new Canvas(bmp);
        int heads = gust >= ARROW3 ? 3 : gust >= ARROW2 ? 2 : 1;
        Path arrow = new Path();
        arrow.moveTo(0, 24);
        arrow.lineTo(0, -22);
        for (int h = 0; h < heads; h++) {
            float dy = h * 13;
            arrow.moveTo(-12, -8 + dy);
            arrow.lineTo(0, -24 + dy);
            arrow.lineTo(12, -8 + dy);
        }
        Matrix m = new Matrix();
        m.postRotate(dir + 180);
        m.postTranslate(50, 36);
        m.postScale(K, K);
        arrow.transform(m);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(6.5f * K);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(GREEN);
        stroke.setShadowLayer(2.2f * K, 0, 0.6f * K, SHADOW);
        c.drawPath(arrow, stroke);

        // "14-30": wind in white, gust smaller
        String a = String.valueOf(speed), b = "-" + gust;
        Paint big = text(27, WHITE, true), small = text(18, MUTED, false);
        float wa = big.measureText(a), x = (SIZE - wa - small.measureText(b)) / 2;
        c.drawText(a, x, 93 * K, big);
        c.drawText(b, x + wa, 93 * K, small);
        return bmp;
    }

    /** Sky emoji on top; underneath, the temperature now and the day's max over min as a fraction. */
    static Bitmap drawTemp(int code, boolean isDay, int now, int max, int min) {
        Bitmap bmp = blank();
        Canvas c = new Canvas(bmp);
        Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);
        sky.setTextSize(46 * K);
        sky.setTextAlign(Paint.Align.CENTER);
        sky.setShadowLayer(2.2f * K, 0, 0.6f * K, 0x60000000);
        c.drawText(emoji(code, isDay), 50 * K, 45 * K, sky);

        String sNow = now + "°", sMax = max + "°", sMin = min + "°";
        Paint big = text(33, WHITE, true), frac = text(17, GOLD, true);
        float wNow = big.measureText(sNow), wFrac = Math.max(frac.measureText(sMax), frac.measureText(sMin)), gap = 5 * K;
        float x = (SIZE - wNow - gap - wFrac) / 2, fx = x + wNow + gap + wFrac / 2;
        c.drawText(sNow, x, 91 * K, big);
        frac.setTextAlign(Paint.Align.CENTER);
        c.drawText(sMax, fx, 73 * K, frac);
        c.drawText(sMin, fx, 94 * K, frac);
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(GOLD);
        bar.setStrokeWidth(1.8f * K);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setShadowLayer(2.2f * K, 0, 0.6f * K, SHADOW);
        c.drawLine(fx - wFrac / 2, 77.5f * K, fx + wFrac / 2, 77.5f * K, bar);
        return bmp;
    }

    /** Two blue waves with the Lido water temperature across them, and the day it was measured underneath. */
    static Bitmap drawLido(int temp, String isoDate) {
        Bitmap bmp = blank();
        Canvas c = new Canvas(bmp);
        Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(12 * K);
        wave.setStrokeCap(Paint.Cap.ROUND);
        int[] colours = {0xFF4AA8FF, 0xFF6FBCFF};
        for (int w = 0; w < 2; w++) {
            float y = (30 + w * 26) * K;
            Path p = new Path();
            p.moveTo(6 * K, y);
            p.quadTo(20 * K, y - 18 * K, 34 * K, y);
            p.quadTo(48 * K, y + 18 * K, 62 * K, y);
            p.quadTo(76 * K, y - 18 * K, 90 * K, y);
            wave.setColor(colours[w]);
            c.drawPath(p, wave);
        }

        // the number: white with a dark-blue outline so it reads on top of the waves
        String s = temp + "°";
        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        num.setTextSize(36 * K);
        num.setTextAlign(Paint.Align.CENTER);
        num.setStyle(Paint.Style.STROKE);
        num.setStrokeWidth(4 * K);
        num.setStrokeJoin(Paint.Join.ROUND);
        num.setColor(0xBF0A3A66);
        c.drawText(s, 50 * K, 55 * K, num);
        num.setStyle(Paint.Style.FILL);
        num.setColor(WHITE);
        c.drawText(s, 50 * K, 55 * K, num);

        String date = "";
        try {
            date = LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK));
        } catch (Exception e) {
            // no date published: show the temperature alone
        }
        Paint small = text(16, MUTED, false);
        small.setTextAlign(Paint.Align.CENTER);
        c.drawText(date, 50 * K, 90 * K, small);
        return bmp;
    }

    /** WMO weather code -> sky emoji (same mapping as the chart). */
    static String emoji(int code, boolean isDay) {
        if (code == 0) return isDay ? "☀️" : "🌙";
        if (code == 1) return isDay ? "🌤️" : "🌙";
        if (code == 2) return isDay ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 61 && code <= 67) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "🌨️";
        if (code == 80) return "🌦️";
        if (code == 81 || code == 82) return "🌧️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }
}
