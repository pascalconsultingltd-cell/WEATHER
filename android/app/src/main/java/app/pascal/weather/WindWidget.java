package app.pascal.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 1x1 home-screen widget: an arrow pointing where the wind is blowing to, with wind-gust speed (km/h) underneath.
 * Same location and arrow rules as the chart: double head from ARROW2 km/h gusts, triple head from ARROW3.
 */
public class WindWidget extends AppWidgetProvider {
    static final double LAT = 51.553, LON = -0.142;
    static final int ARROW2 = 30, ARROW3 = 45;
    static final String API = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
            + "&current=wind_speed_10m,wind_gusts_10m,wind_direction_10m&wind_speed_unit=kmh";
    static final String PREFS = "wind";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context);                         // show the last known values straight away
        refresh(context, goAsync());             // then fetch fresh ones in the background
    }

    /** Fetches the current wind off the main thread, stores it and redraws every widget. */
    static void refresh(final Context context, final PendingResult pending) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject cur = new JSONObject(get(API)).getJSONObject("current");
                    int speed = (int) Math.round(cur.getDouble("wind_speed_10m"));
                    int gust = Math.max(speed, (int) Math.round(cur.optDouble("wind_gusts_10m", speed)));
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("speed", speed)
                            .putInt("gust", gust)
                            .putFloat("dir", (float) cur.optDouble("wind_direction_10m", 0))
                            .apply();
                    render(context);
                } catch (Exception e) {
                    // offline or the service is down: the widget keeps showing the last values
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

    static void render(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WindWidget.class));
        if (ids.length == 0) return;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_wind);
        views.setImageViewBitmap(R.id.img, draw(p.getInt("speed", -1), p.getInt("gust", -1), p.getFloat("dir", 0)));
        // tapping the widget opens the chart
        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(ids, views);
    }

    /** Draws the widget face on a 100x100 grid scaled up to SIZE pixels. speed < 0 means no data yet. */
    static Bitmap draw(int speed, int gust, float dir) {
        final int SIZE = 300;
        final float k = SIZE / 100f;
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        if (speed < 0) {
            text.setColor(0xFF92A6B3);
            text.setTextSize(30 * k);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("…", 50 * k, 58 * k, text);
            return bmp;
        }

        // the arrow points where the wind blows TO (bearing it comes from + 180)
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
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(6.5f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFF3DDC84);
        c.save();
        c.scale(k, k);
        c.translate(50, 36);
        c.rotate(dir + 180);
        c.drawPath(arrow, stroke);
        c.restore();

        // "14-30": wind in white, gust smaller and muted
        String a = String.valueOf(speed), b = "-" + gust;
        Paint small = new Paint(text);
        small.setTypeface(Typeface.MONOSPACE);
        text.setColor(0xFFE5EEF3);
        text.setTextSize(27 * k);
        small.setColor(0xFF92A6B3);
        small.setTextSize(18 * k);
        float wa = text.measureText(a), x = (SIZE - wa - small.measureText(b)) / 2;
        c.drawText(a, x, 93 * k, text);
        c.drawText(b, x + wa, 93 * k, small);
        return bmp;
    }
}
