package tw.eppt.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String DECK = "ePPT.pdf";
    private static final int BLUE = 0xff173b5a;
    private PdfRenderer renderer;
    private HandlerThread renderingThread;
    private Handler worker;
    private SlideView slide;
    private WebView web;
    private Button previous;
    private Button next;
    private Button pageButton;
    private int page;
    private int count;
    private int request;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try {
            renderer = openDeck();
        } catch (IOException | RuntimeException error) {
            showLegacyPresentation();
            return;
        }
        count = renderer.getPageCount();
        renderingThread = new HandlerThread("ePPT PDF renderer");
        renderingThread.start();
        worker = new Handler(renderingThread.getLooper());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BLUE);
        slide = new SlideView();
        column.addView(slide, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        previous = button("上一張", view -> showPage(page - 1));
        next = button("下一張", view -> showPage(page + 1));
        pageButton = button("", view -> choosePage());
        for (Button button : new Button[]{previous, pageButton, next}) {
            controls.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        column.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        setContentView(column);
        showPage(saved == null ? getPreferences(MODE_PRIVATE).getInt("page", 0)
            : saved.getInt("page", 0));
    }

    private PdfRenderer openDeck() throws IOException {
        // PdfRenderer needs a seekable descriptor starting at offset zero.
        File copy = new File(getCacheDir(), DECK);
        try (InputStream input = getAssets().open(DECK);
             FileOutputStream output = new FileOutputStream(copy)) {
            byte[] buffer = new byte[64 * 1024];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
        }
        ParcelFileDescriptor file = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            return new PdfRenderer(file);
        } catch (IOException | RuntimeException error) {
            file.close();
            throw error;
        }
    }

    private Button button(String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(BLUE);
        button.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(listener);
        return button;
    }

    private void showPage(int index) {
        page = Math.max(0, Math.min(index, count - 1));
        previous.setEnabled(page > 0);
        next.setEnabled(page + 1 < count);
        pageButton.setText((page + 1) + " / " + count);
        pageButton.setContentDescription("跳至投影片，目前第 " + (page + 1) + " 張，共 " + count + " 張");
        getPreferences(MODE_PRIVATE).edit().putInt("page", page).apply();
        int selected = page;
        int ticket = ++request;
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            Bitmap image;
            try (PdfRenderer.Page pdfPage = renderer.openPage(selected)) {
                int width = 1800;
                int height = Math.round((float) width * pdfPage.getHeight() / pdfPage.getWidth());
                image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                image.eraseColor(Color.WHITE);
                Matrix scale = new Matrix();
                scale.setScale((float) width / pdfPage.getWidth(), (float) height / pdfPage.getHeight());
                pdfPage.render(image, null, scale, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (ticket == request && !isDestroyed())
                        Toast.makeText(this, "無法顯示此投影片", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            runOnUiThread(() -> {
                if (ticket != request || isDestroyed()) image.recycle();
                else slide.replace(image, selected);
            });
        });
    }

    private void choosePage() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1–" + count);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("跳至投影片")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("前往", null)
            .create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int number = Integer.parseInt(input.getText().toString());
                if (number < 1 || number > count) throw new NumberFormatException();
                showPage(number - 1);
                dialog.dismiss();
            } catch (NumberFormatException error) {
                input.setError("請輸入 1 到 " + count + " 的數字");
            }
        }));
        dialog.show();
    }

    private void showLegacyPresentation() {
        web = new WebView(this);
        web.setWebViewClient(new WebViewClient());
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBuiltInZoomControls(false);
        setContentView(web);
        web.loadUrl("file:///android_asset/site/ePPT/index.html");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(renderer::close);
            renderingThread.quitSafely();
        }
        if (slide != null) slide.release();
        super.onDestroy();
    }

    private class SlideView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final GestureDetector gestures;
        private final ScaleGestureDetector pinch;
        private Bitmap bitmap;
        private float zoom = 1f;
        private float x;
        private float y;
        private boolean multiTouch;

        SlideView() {
            super(MainActivity.this);
            setBackgroundColor(0xff102435);
            gestures = new GestureDetector(MainActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }

                @Override public boolean onDoubleTap(MotionEvent event) {
                    zoom = zoom > 1f ? 1f : 2.5f;
                    constrain();
                    invalidate();
                    return true;
                }

                @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                    if (zoom > 1f && !pinch.isInProgress()) {
                        x -= dx;
                        y -= dy;
                        constrain();
                        invalidate();
                    }
                    return true;
                }
            });
            pinch = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom = Math.max(1f, Math.min(4f, zoom * detector.getScaleFactor()));
                    constrain();
                    invalidate();
                    return true;
                }
            });
        }

        private float downX;
        private float downY;

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                multiTouch = false;
            }
            if (event.getPointerCount() > 1) multiTouch = true;
            pinch.onTouchEvent(event);
            gestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !multiTouch && zoom == 1f) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float threshold = 44 * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > threshold && Math.abs(dx) > 1.4f * Math.abs(dy)) {
                    showPage(page + (dx < 0 ? 1 : -1));
                }
            }
            return true;
        }

        void replace(Bitmap image, int selected) {
            release();
            bitmap = image;
            zoom = 1f;
            x = y = 0f;
            setContentDescription("第 " + (selected + 1) + " 張投影片");
            invalidate();
        }

        void release() {
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
        }

        private float scale() {
            return zoom * Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        }

        private void constrain() {
            if (bitmap == null) return;
            float maxX = Math.max(0, (bitmap.getWidth() * scale() - getWidth()) / 2);
            float maxY = Math.max(0, (bitmap.getHeight() * scale() - getHeight()) / 2);
            x = Math.max(-maxX, Math.min(maxX, x));
            y = Math.max(-maxY, Math.min(maxY, y));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            float factor = scale();
            canvas.save();
            canvas.translate((getWidth() - bitmap.getWidth() * factor) / 2 + x,
                (getHeight() - bitmap.getHeight() * factor) / 2 + y);
            canvas.scale(factor, factor);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            canvas.restore();
        }
    }
}
