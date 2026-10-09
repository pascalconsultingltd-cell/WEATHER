package app.pascal.weather;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The app screen: the weather chart page, shown full screen. */
public class MainActivity extends Activity {
    static final String CHART_URL = "https://pascalconsultingltd-cell.github.io/WEATHER/";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());   // keep navigation inside the app
        web.setBackgroundColor(0xFFFFFFFF);
        setContentView(web);
        web.loadUrl(CHART_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // coming back to the app reloads the forecast so the chart starts at the real "now"
        web.onResume();
        web.evaluateJavascript("if (typeof load === 'function') load();", null);
        // and brings the widgets up to date
        Weather.refresh(getApplicationContext(), null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }
}
