package app.pascal.weather;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 1x1 home-screen widget: sky emoji, temperature now and the day's max over min. Drawn by {@link Weather}. */
public class TempWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Weather.render(context);                 // show the last known values straight away
        Weather.refresh(context, goAsync());     // then fetch fresh ones in the background
    }
}
