package tw.eppt.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final List<String> slideTitles = new ArrayList<>();
    private PopupWindow slideMenu;

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
        loadSlideTitles();
        renderingThread = new HandlerThread("ePPT PDF renderer");
        renderingThread.start();
        worker = new Handler(renderingThread.getLooper());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BLUE);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button slides = button("投影片 ▾", this::showSlideMenu);
        slides.setContentDescription("選擇投影片");
        slides.setPadding(dp(18), 0, dp(18), 0);
        header.addView(slides, new LinearLayout.LayoutParams(-2, dp(48)));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadSlideTitles() {
        try (BufferedReader titleFile = new BufferedReader(new InputStreamReader(
                getAssets().open("slide-titles.txt"), StandardCharsets.UTF_8))) {
            String title;
            while (slideTitles.size() < count && (title = titleFile.readLine()) != null) {
                slideTitles.add(title);
            }
        } catch (IOException error) {
            // Slide numbers remain available when the optional title file is absent.
        }
        while (slideTitles.size() < count) slideTitles.add("");
    }

    private void showSlideMenu(View anchor) {
        if (slideMenu != null && slideMenu.isShowing()) {
            slideMenu.dismiss();
            return;
        }
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundColor(Color.WHITE);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜尋標題或頁碼");
        search.setTextColor(BLUE);
        search.setTextSize(16);
        search.setPadding(dp(14), 0, dp(14), 0);
        menu.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));

        ListView pages = new ListView(this);
        pages.setDividerHeight(dp(1));
        SlideAdapter options = new SlideAdapter();
        pages.setAdapter(options);
        menu.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.setOnItemClickListener((parent, row, position, id) -> {
            int selected = options.getItem(position);
            slideMenu.dismiss();
            showPage(selected);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                options.filter(s.toString());
                pages.setSelection(0);
            }
            @Override public void afterTextChanged(Editable text) {}
        });

        View screen = getWindow().getDecorView();
        int width = Math.min(dp(370), screen.getWidth());
        int height = Math.min(dp(510), screen.getHeight() - anchor.getHeight() - dp(12));
        slideMenu = new PopupWindow(menu, width, height, true);
        slideMenu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        slideMenu.setElevation(dp(8));
        slideMenu.setOutsideTouchable(true);
        slideMenu.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        slideMenu.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        menu.setFocusableInTouchMode(true);
        menu.requestFocus();
        slideMenu.showAsDropDown(anchor, 0, 0, Gravity.END);
        pages.setSelection(Math.max(0, page - 1));
    }

    private class SlideAdapter extends BaseAdapter {
        private final List<Integer> matches = new ArrayList<>();

        SlideAdapter() { filter(""); }

        void filter(String query) {
            String term = query.trim().toLowerCase(Locale.ROOT);
            boolean pageNumber = term.matches("[0-9]+");
            matches.clear();
            for (int i = 0; i < count; i++) {
                String title = slideTitles.get(i).toLowerCase(Locale.ROOT);
                if (pageNumber ? term.equals(Integer.toString(i + 1)) : title.contains(term)) {
                    matches.add(i);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return matches.size(); }
        @Override public Integer getItem(int position) { return matches.get(position); }
        @Override public long getItemId(int position) { return getItem(position); }

        @Override public View getView(int position, View oldRow, ViewGroup parent) {
            SlideRow row = oldRow instanceof SlideRow ? (SlideRow) oldRow : new SlideRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private class SlideRow extends LinearLayout {
        private final ImageView thumbnail;
        private final TextView title;

        SlideRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(10), dp(6), dp(12), dp(6));
            thumbnail = new ImageView(MainActivity.this);
            thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(thumbnail, new LinearLayout.LayoutParams(dp(80), dp(60)));
            title = new TextView(MainActivity.this);
            title.setTextSize(15);
            title.setTextColor(BLUE);
            title.setPadding(dp(10), 0, 0, 0);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        }

        void bind(int index) {
            String label = slideTitles.get(index);
            title.setText((index + 1) + "  " + (label.isEmpty() ? "投影片" : label));
            setBackgroundColor(index == page ? 0xffdeefff : Color.WHITE);
            thumbnail.setImageDrawable(null);
            try (InputStream image = getAssets().open("slide-thumbnails/" + (index + 1) + ".jpg")) {
                thumbnail.setImageBitmap(BitmapFactory.decodeStream(image));
            } catch (IOException error) {
                // Missing previews do not prevent navigation.
            }
        }
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
        if (slideMenu != null) slideMenu.dismiss();
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
