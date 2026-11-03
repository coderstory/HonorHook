package top.honorhook.ui.webscan;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

public class WebScanActivity extends Activity {

    private static final String TAG = "WebScan";
    private static final int REQ_CAMERA = 1;

    /** 双写日志：logcat + /sdcard/Download/zcodeweb.log（便于无 adb 时取证） */
    private void log(String msg) {
        android.util.Log.d(TAG, msg);
        try {
            java.io.File f = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "zcodeweb.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date())
                    + " " + msg + "\n");
            w.close();
        } catch (Exception ignore) { }
    }
    private static final int REQ_PICK_IMAGE = 2;
    private static final String PREFS = "webscan_hh";
    private static final String KEY_URL = "last_url";

    // 扫码界面
    private FrameLayout scanView;
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private ScannerOverlay scannerOverlay;

    // 浏览界面
    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout webWrap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("onCreate: savedInstanceState=" + (savedInstanceState != null));
        buildUi();

        // 自动恢复上次浏览状态：有历史网址就直接回到上次页面（Cookie/DOM Storage 落盘自动带回来）；
        // 返回键可退回扫码页重新扫。
        String last = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, null);
        if (last != null) {
            openUrl(last);
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    // ================= UI =================

    private void buildUi() {
        float den = getResources().getDisplayMetrics().density;
        float den0 = getResources().getDisplayMetrics().density;
        // 卡片宽度固定 100%（2026-11-03 用户需求）：屏宽 - 两侧 16dp 边距，正方形
        int cardSide = getResources().getDisplayMetrics().widthPixels - Math.round(16 * den0 * 2);

        scanView = new FrameLayout(this);
        scanView.setBackgroundColor(0xFFFFFFFF);

        // ---- 中央取景卡片（圆角裁剪的相机预览 + 科技感遮罩）----
        FrameLayout card = new FrameLayout(this);
        card.setBackground(roundRect(0xFFF2F3F5, dp(26), 0, 0x1A000000));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(26));
            }
        });

        textureView = new TextureView(this);
        card.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scannerOverlay = new ScannerOverlay(this);
        card.addView(scannerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardSide, cardSide,
                Gravity.CENTER);
        cardLp.bottomMargin = dp(60);
        cardLp.setMargins(dp(16), 0, dp(16), 0);
        scanView.addView(card, cardLp);

        // ---- 底部：状态 + 相册按钮 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView status = new TextView(this);
        status.setText("对准取景框 · 自动识别网址");
        status.setTextColor(0x8A17171A);
        status.setTextSize(13);

        android.widget.Button albumBtn = new android.widget.Button(this);
        albumBtn.setText("从相册识别");
        albumBtn.setTextColor(0xFF17171A);
        albumBtn.setTextSize(15);
        albumBtn.setAllCaps(false);
        albumBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        albumBtn.setPadding(dp(30), 0, dp(30), 0);
        albumBtn.setStateListAnimator(null);
        albumBtn.setBackground(roundRect(0xFFFFFFFF, dp(24), dp(1), 0x1A000000));
        albumBtn.setOnClickListener(v ->
                startActivityForResult(new android.content.Intent(
                        android.content.Intent.ACTION_GET_CONTENT)
                        .setType("image/*").addCategory(android.content.Intent.CATEGORY_OPENABLE),
                        REQ_PICK_IMAGE));
        LinearLayout.LayoutParams albumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        albumLp.topMargin = dp(18);
        albumBtn.setLayoutParams(albumLp);

        bottom.addView(status);
        bottom.addView(albumBtn);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottomLp.bottomMargin = dp(56);
        scanView.addView(bottom, bottomLp);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        webView.setBackgroundColor(Color.WHITE);
        setupWebView();

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);

        // 网页容器：FrameLayout（进度条+WebView+悬浮菜单钮）
        LinearLayout webColumn = new LinearLayout(this);
        webColumn.setOrientation(LinearLayout.VERTICAL);
        webColumn.addView(progressBar);
        webColumn.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        webWrap = new FrameLayout(this);
        webWrap.setVisibility(View.GONE);   // 初始隐藏：否则返回键永远误判在网页态
        webWrap.addView(webColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.widget.TextView menuBtn = new android.widget.TextView(this);
        menuBtn.setText("☰");
        menuBtn.setTextColor(0xCC17171A);
        menuBtn.setTextSize(18);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setBackground(roundRect(0x8AFFFFFF, dp(22), dp(1), 0x1A000000));
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        menuBtn.setLayoutParams(menuLp);
        setupDraggableFab(menuBtn, menuLp);
        menuBtn.setOnClickListener(v -> showWebMenu());
        webWrap.addView(menuBtn, menuLp);

        FrameLayout root = new FrameLayout(this);
        root.addView(scanView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 二级页化（2026-11-03 用户需求）：顶栏「← 远程开发」与工具箱其他二级页一致
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFFFFFFF);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(0xFF17171A);
        back.setTextSize(22);
        back.setPadding(dp(20), dp(10), dp(20), dp(10));
        back.setOnClickListener(v -> finish());
        TextView pageTitle = new TextView(this);
        pageTitle.setText("远程开发");
        pageTitle.setTextColor(0xFF17171A);
        pageTitle.setTextSize(18);
        pageTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topBar.addView(back);
        topBar.addView(pageTitle);
        page.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        page.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(page);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        android.webkit.WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);   // 禁止无手势自动播视频吃 CPU/GPU
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        // 渲染器降级：WebView 不可见时主动让出 GPU/CPU（API 26+）
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            webView.setRendererPriorityPolicy(
                    android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        // ---- 缓存策略：正常走 HTTP 缓存；加载失败时回退强制读缓存（离线可用）----
        s.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
                                                    android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override
            public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                log("onPageStarted: " + url);
            }

            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                log("onPageFinished: " + url);
            }

        });
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(android.webkit.WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        });
    }

    private static final String KEY_HISTORY = "history";

    /** URL 归一（2026-11-03 用户需求）：判同去掉 query 参数，仅保留 scheme://host/path */
    private static String normUrl(String u) {
        try {
            java.net.URI uri = new java.net.URI(u);
            return new java.net.URI(uri.getScheme(), uri.getHost(), uri.getPath(), null).toString();
        } catch (Exception e) {
            int q = u.indexOf('?');
            return q < 0 ? u : u.substring(0, q);
        }
    }

    /** 记录打开历史：按去参 URL 判重置顶，最多 10 条 */
    private void recordHistory(String url) {
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        for (String u : getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY, "").split("\n")) {
            if (!u.isEmpty()) q.add(u);
        }
        String nn = normUrl(url);
        q.removeIf(x -> normUrl(x).equals(nn));   // 去参判重
        q.addFirst(url);        // 置顶
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String u : q) {
            if (i++ >= 10) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(u);
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private java.util.List<String> readHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String u : getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY, "").split("\n")) {
            if (!u.isEmpty()) out.add(u);
        }
        return out;
    }

    private android.app.AlertDialog historyDialog;

    /** 历史记录弹窗：最近 10 个地址，点击打开 */
    private void showHistory() {
        java.util.List<String> items = readHistory();
        if (items.isEmpty()) {
            toast("暂无打开记录");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView head = new TextView(this);
        head.setText("最近打开（新 → 旧）· 长按复制地址");
        head.setTextColor(0x8A17171A);
        head.setTextSize(12);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(4);
        box.addView(head, headLp);

        for (String url : items) {
            TextView row = new TextView(this);
            row.setText(url);
            row.setTextColor(0xFF17171A);
            row.setTextSize(14);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            row.setBackground(roundRect(0xFFF2F3F5, dp(10), 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.topMargin = dp(4);
            row.setOnClickListener(v -> {
                if (historyDialog != null) historyDialog.dismiss();
                openUrl(url);
            });
            // 长按复制地址（2026-11-03 用户需求）
            row.setOnLongClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                toast("已复制地址");
                return true;
            });
            box.addView(row, lp);
        }

        historyDialog = new android.app.AlertDialog.Builder(this).create();
        historyDialog.setView(box);
        historyDialog.show();
        if (historyDialog.getWindow() != null) {
            historyDialog.getWindow().setBackgroundDrawable(
                    roundRect(0xFFFFFFFF, dp(18), dp(1), 0x1A000000));
            historyDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void openUrl(String url) {
        log("openUrl: " + url);
        recordHistory(url);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_URL, url).apply();
        scanView.setVisibility(View.GONE);
        webWrap.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
        stopCamera();
        webView.loadUrl(url);
    }

    /** 返回键：网页后退 → 确认回扫码页；扫码页 → 确认退出 */
    @Override
    public void onBackPressed() {
        log("onBackPressed: webWrap=" + (webWrap.getVisibility() == View.VISIBLE)
                + " scanView=" + (scanView.getVisibility() == View.VISIBLE)
                + " canGoBack=" + webView.canGoBack());
        if (webWrap.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                showConfirm("返回扫码", "退出当前网页，返回扫码页？\n已登录状态会保留。",
                        "返回扫码", this::backToScanner, "留在本页");
            }
            return;
        }
        if (scanView.getVisibility() == View.VISIBLE) {
            // 返回主页面（2026-11-03 用户需求）：回模块主界面，不再说「退出应用」
            showConfirm("返回主页面", "返回模块主界面？\n已登录状态会保留。", "返回", this::finish, "留在本页");
            return;
        }
        super.onBackPressed();
    }

    /** 悬浮球：可拖动，松手吸附左右边缘，位置持久化；位移小于阈值视为点击 */
    private void setupDraggableFab(View fab, FrameLayout.LayoutParams lp) {
        final int[] down = new int[2];
        final float[] downRaw = new float[2];
        final boolean[] dragged = {false};

        // 恢复上次位置（默认右下角）
        android.graphics.Point saved = readFabPos();
        if (saved != null) {
            fab.post(() -> {
                int maxX = Math.max(0, webWrap.getWidth() - fab.getWidth());
                int maxY = Math.max(0, webWrap.getHeight() - fab.getHeight());
                lp.leftMargin = Math.min(saved.x, maxX);
                lp.topMargin = Math.min(saved.y, maxY);
                fab.setLayoutParams(lp);
            });
        } else {
            fab.post(() -> {
                lp.leftMargin = Math.max(0, webWrap.getWidth() - fab.getWidth() - dp(14));
                lp.topMargin = Math.max(0, webWrap.getHeight() - fab.getHeight() - dp(20));
                fab.setLayoutParams(lp);
            });
        }

        fab.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = lp.leftMargin;
                    down[1] = lp.topMargin;
                    downRaw[0] = ev.getRawX();
                    downRaw[1] = ev.getRawY();
                    dragged[0] = false;
                    return true;   // 自己消费， onClick 由松手判定后手动触发
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downRaw[0];
                    float dy = ev.getRawY() - downRaw[1];
                    if (!dragged[0] && Math.hypot(dx, dy) < dp(6)) return true;
                    dragged[0] = true;
                    int maxX = Math.max(0, webWrap.getWidth() - fab.getWidth());
                    int maxY = Math.max(0, webWrap.getHeight() - fab.getHeight());
                    lp.leftMargin = Math.max(0, Math.min((int) (down[0] + dx), maxX));
                    lp.topMargin = Math.max(0, Math.min((int) (down[1] + dy), maxY));
                    fab.setLayoutParams(lp);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP: {
                    if (!dragged[0]) {
                        v.performClick();          // 原地点击 → 菜单
                        return true;
                    }
                    // 吸附最近边缘（x 动画省略：直接落位）
                    int centerX = lp.leftMargin + fab.getWidth() / 2;
                    boolean toLeft = centerX < webWrap.getWidth() / 2f;
                    lp.leftMargin = toLeft ? dp(10)
                            : Math.max(0, webWrap.getWidth() - fab.getWidth() - dp(10));
                    fab.setLayoutParams(lp);
                    saveFabPos(lp.leftMargin, lp.topMargin);
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private void saveFabPos(int x, int y) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("fab_x", x).putInt("fab_y", y).apply();
    }

    private android.graphics.Point readFabPos() {
        android.content.SharedPreferences p =
                getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("fab_x")) return null;
        return new android.graphics.Point(p.getInt("fab_x", 0), p.getInt("fab_y", 0));
    }

    /** 网页内悬浮菜单：返回主页面 / 刷新 / 最近打开 / 返回扫码（点击任意项自动收起） */
    private void showWebMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(6), dp(10), dp(6));

        box.addView(menuItem("刷新页面", () -> { dismissWebMenu(); webView.reload(); }),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        box.addView(menuItem("返回主页面", () -> { dismissWebMenu(); finish(); }),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        box.addView(menuItem("最近打开的地址", () -> { dismissWebMenu(); showHistory(); }),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        box.addView(menuItem("返回扫码页", () -> { dismissWebMenu(); backToScanner(); }),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        webMenuDialog = new android.app.AlertDialog.Builder(this).create();
        webMenuDialog.setView(box);
        webMenuDialog.show();
        if (webMenuDialog.getWindow() != null) {
            webMenuDialog.getWindow().setBackgroundDrawable(roundRect(0xFFFFFFFF, dp(18), dp(1), 0x1A000000));
            webMenuDialog.getWindow().setLayout(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private android.app.AlertDialog webMenuDialog;

    private void dismissWebMenu() {
        if (webMenuDialog != null) { webMenuDialog.dismiss(); webMenuDialog = null; }
    }

    private android.widget.Button menuItem(String label, Runnable action) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setTextColor(0xFF17171A);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setStateListAnimator(null);
        b.setBackground(null);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** 统一样式确认弹窗：深色圆角卡片 + 渐变主按钮 + 描边次按钮 */
    private void showConfirm(String title, String message,
                             String positiveText, Runnable onPositive,
                             String negativeText) {
        float den = getResources().getDisplayMetrics().density;
        int pad = (int) (22 * den);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, (int) (16 * den));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFF17171A);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(tv);

        TextView msg = new TextView(this);
        msg.setText(message);
        msg.setTextColor(0x8A17171A);
        msg.setTextSize(14);
        msg.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(10);
        box.addView(msg, msgLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(22);
        row.setLayoutParams(rowLp);

        android.widget.Button neg = new android.widget.Button(this);
        neg.setText(negativeText);
        neg.setTextColor(0xFF17171A);
        neg.setTextSize(14);
        neg.setAllCaps(false);
        neg.setStateListAnimator(null);
        neg.setBackground(roundRect(0x00000000, dp(22), dp(1), 0x1A000000));
        neg.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams negLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        row.addView(neg, negLp);

        android.widget.Button pos = new android.widget.Button(this);
        pos.setText(positiveText);
        pos.setTextColor(0xFFFFFFFF);
        pos.setTextSize(14);
        pos.setAllCaps(false);
        pos.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pos.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
        pg.setColor(0xFF17171A);
        pg.setCornerRadius(dp(22));
        pos.setBackground(pg);
        pos.setPadding(dp(22), 0, dp(22), 0);
        LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        posLp.leftMargin = dp(10);
        row.addView(pos, posLp);

        box.addView(row);

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).create();
        d.setView(box);
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(roundRect(0xFFFFFFFF, dp(22), dp(1), 0x1A000000));
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        neg.setOnClickListener(v -> d.dismiss());
        pos.setOnClickListener(v -> {
            d.dismiss();
            if (onPositive != null) onPositive.run();
        });
    }

    private void backToScanner() {
        webWrap.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        scanView.setVisibility(View.VISIBLE);
        startCameraWhenReady();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraWhenReady();
            } else {
                toast("需要相机权限扫码");
                // 无相机权限时回退：直接打开上次缓存的地址
                String last = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_URL, null);
                if (last != null) openUrl(last);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            try {
                android.graphics.Bitmap bmp = android.provider.MediaStore.Images.Media
                        .getBitmap(getContentResolver(), data.getData());
                int[] pixels = new int[bmp.getWidth() * bmp.getHeight()];
                bmp.getPixels(pixels, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
                com.google.zxing.RGBLuminanceSource src = new com.google.zxing.RGBLuminanceSource(
                        bmp.getWidth(), bmp.getHeight(), pixels);
                MultiFormatReader reader = new MultiFormatReader();
                Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                hints.put(DecodeHintType.POSSIBLE_FORMATS,
                        Collections.singletonList(BarcodeFormat.QR_CODE));
                hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                reader.setHints(hints);
                com.google.zxing.Result r = reader.decodeWithState(new BinaryBitmap(
                        new HybridBinarizer(src)));
                String text = r.getText();
                if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                    openUrl(text.trim());
                } else {
                    toast("二维码内容不是网址");
                }
            } catch (Exception e) {
                toast("未识别到二维码");
            }
        }
    }

    // ================= Camera2 + ZXing =================

    private void startCameraWhenReady() {
        ensureCameraThread();
        if (textureView.isAvailable()) {
            openCameraRetryable();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCameraRetryable(); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
        }
    }

    /** 相机线程常驻（不随 stopCamera 退出），避免「回扫码页时 handler 已死」的竞态 */
    private void ensureCameraThread() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("cam");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    private int openRetry = 0;

    /** 打开失败自动重试（相机服务在快速 close→reopen 后常短暂 busy） */
    private void openCameraRetryable() {
        try {
            openCamera();
            openRetry = 0;
        } catch (Exception e) {
            log("openCamera failed(" + openRetry + "): " + e);
            if (++openRetry <= 3 && cameraHandler != null) {
                cameraHandler.postDelayed(this::openCameraRetryable, 400);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        cameraThread = new HandlerThread("cam");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String camId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { camId = id; break; }
            }
            if (camId == null && cm.getCameraIdList().length > 0) camId = cm.getCameraIdList()[0];
            if (camId == null) { toast("没有可用相机"); return; }

            final String id = camId;
            CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            int sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) != null
                    ? ch.get(CameraCharacteristics.SENSOR_ORIENTATION) : 0;

            Size best = null;
            for (Size size : ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceTexture.class)) {
                if (best == null || best.getWidth() * best.getHeight() < size.getWidth() * size.getHeight()) {
                    if (size.getWidth() <= 1600 && size.getHeight() <= 1600) best = size;
                }
            }
            if (best == null) {
                android.util.Size[] sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(SurfaceTexture.class);
                best = sizes[0];
            }

            imageReader = ImageReader.newInstance(best.getWidth(), best.getHeight(),
                    android.graphics.ImageFormat.YUV_420_888, 2);
            final Size previewSize = best;
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                try {
                    if (decoding.compareAndSet(false, true)) {
                        int iw = img.getWidth(), ih = img.getHeight();
                        byte[] yuv = yuv420ToNv21(img);
                        img.close();
                        tryDecode(yuv, iw, ih, sensorOrientation);
                        return;
                    }
                } finally {
                    img.close();
                }
            }, cameraHandler);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        Surface preview = new Surface(textureView.getSurfaceTexture());
                        textureView.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(preview);
                        builder.addTarget(imageReader.getSurface());
                        java.util.List<Surface> targets = new java.util.ArrayList<>();
                        targets.add(preview);
                        targets.add(imageReader.getSurface());
                        camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    builder.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                    log("camera session streaming ok");
                                } catch (Exception e) {
                                    log("setRepeatingRequest failed: " + e);
                                    runOnUiThread(() -> { stopCamera(); openCameraRetryable(); });
                                }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                log("onConfigureFailed");
                                runOnUiThread(() -> { stopCamera(); openCameraRetryable(); });
                            }
                        }, cameraHandler);
                    } catch (Exception e) {
                        throw new RuntimeException("session: " + e);
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, cameraHandler);
        } catch (Exception e) {
            throw new RuntimeException("open: " + e);
        }
    }

    private void tryDecode(byte[] nv21, int w, int h, int sensorOrientation) {
        try {
            // 竖屏应用 + 横向传感器：亮度平面旋转 90°
            byte[] rotated = rotateY(nv21, w, h, sensorOrientation >= 270 ? 270 : 90);
            // rotateY 输出为 (h x w) —— 90/270 交换宽高
            int outW = h, outH = w;

            // 取景框（卡片坐标）→ 旋转后图像坐标：把解码范围对齐到用户看到的框内
            int cl = 0, ct = 0, cw = outW, ch = outH;
            if (scannerOverlay != null && textureView.getWidth() > 0) {
                android.graphics.RectF f = scannerOverlay.getFrameRect();
                float sx = (float) outW / textureView.getWidth();
                float sy = (float) outH / textureView.getHeight();
                cl = Math.max(0, (int) (f.left * sx));
                ct = Math.max(0, (int) (f.top * sy));
                cw = Math.min(outW - cl, (int) (f.width() * sx));
                ch = Math.min(outH - ct, (int) (f.height() * sy));
            }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    rotated, outW, outH, cl, ct, cw, ch, false);
            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hints);
            com.google.zxing.Result r = reader.decodeWithState(new BinaryBitmap(
                    new HybridBinarizer(src)));
            String text = r.getText();
            if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                final String url = text.trim();
                runOnUiThread(() -> openUrl(url));
            }
            // 非网址的二维码：忽略，继续扫
        } catch (Exception ignore) {
            // 未识别到二维码（正常帧）
        } finally {
            decoding.set(false);
        }
    }

    private void stopCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignore) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignore) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception ignore) {}
        // 相机线程常驻不退出：退出会造成「回扫码页时 handler 已死/请求静默失败」的竞态（画面冻帧）
    }

    private static byte[] yuv420ToNv21(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane y = img.getPlanes()[0], u = img.getPlanes()[1], v = img.getPlanes()[2];
        ByteBuffer yb = y.getBuffer(), ub = u.getBuffer(), vb = v.getBuffer();
        int ySize = w * h;
        byte[] out = new byte[ySize + ySize / 2];
        int yRow = y.getRowStride();
        for (int row = 0; row < h; row++) {
            yb.get(row * yRow, out, row * w, w);
        }
        int pos = ySize;
        // 交错 VU（NV21）；按行处理像素跨度
        int chromaH = h / 2, chromaW = w / 2;
        int uRow = u.getRowStride(), uPix = u.getPixelStride();
        int vRow = v.getRowStride(), vPix = v.getPixelStride();
        for (int row = 0; row < chromaH; row++) {
            for (int col = 0; col < chromaW; col++) {
                out[pos++] = vb.get(row * vRow + col * vPix);
                out[pos++] = ub.get(row * uRow + col * uPix);
            }
        }
        return out;
    }

    /** 只旋转 Y 平面（解码只用亮度） */
    private static byte[] rotateY(byte[] data, int w, int h, int degrees) {
        byte[] out = new byte[w * h];
        if (degrees == 90) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    out[x * h + (h - 1 - y)] = data[y * w + x];
        } else if (degrees == 270) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    out[(w - 1 - x) * h + y] = data[y * w + x];
        } else {
            System.arraycopy(data, 0, out, 0, w * h);
        }
        return out;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.GradientDrawable roundRect(
            int fill, float radius, float strokeW, int strokeColor) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeW > 0) {
            d.setStroke((int) strokeW, strokeColor);
        }
        return d;
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        if (cameraThread != null) cameraThread.quitSafely();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Cookie 立即落盘，保证状态恢复
        android.webkit.CookieManager.getInstance().flush();
        if (webWrap.getVisibility() == View.VISIBLE) {
            // 后台冻结网页：停渲染 + 全局暂停 JS 定时器/动画
            webView.onPause();
            webView.pauseTimers();
            log("webview frozen (background)");
        } else {
            stopCamera();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            log("onKeyDown: BACK received");
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("onResume: scanView=" + (scanView.getVisibility() == View.VISIBLE));
        if (webWrap.getVisibility() == View.VISIBLE) {
            webView.onResume();
            webView.resumeTimers();
            log("webview resumed");
        }
        if (scanView.getVisibility() == View.VISIBLE
                && cameraDevice == null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady();
        }
    }
}
