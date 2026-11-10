package tw.eppt.viewer;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
public class MainActivity extends Activity {
 private WebView web;
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
  web = new WebView(this);
  web.setWebViewClient(new WebViewClient());
  WebSettings s = web.getSettings();
  s.setJavaScriptEnabled(true);
  s.setDomStorageEnabled(true);
  s.setAllowFileAccess(true);
  s.setAllowContentAccess(true);
  s.setAllowFileAccessFromFileURLs(true);
  s.setAllowUniversalAccessFromFileURLs(false);
  s.setBuiltInZoomControls(false);
  setContentView(web);
  web.loadUrl("file:///android_asset/site/ePPT/index.html");
 }
 @Override public void onBackPressed() { if(web.canGoBack())web.goBack(); else super.onBackPressed(); }
}