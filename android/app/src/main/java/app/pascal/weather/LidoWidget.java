package app.pascal.weather;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 1x1 home-screen widget: Hampstead Heath Lido water temperature over two waves, with the reading's date. Drawn by {@link Weather}. */
public class LidoWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Weather.render(context);                 // show the last known values straight away
        Weather.refresh(context, goAsync());     // then fetch fresh ones in the background
    }
}
