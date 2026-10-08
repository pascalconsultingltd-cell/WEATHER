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
        web.setBackgroundColor(0xFF162630);
        setContentView(web);
        web.loadUrl(CHART_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // opening the app also brings the widget up to date
        WindWidget.refresh(getApplicationContext(), null);
    }
}
