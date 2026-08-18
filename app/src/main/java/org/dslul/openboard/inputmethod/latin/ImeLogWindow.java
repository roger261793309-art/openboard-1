package org.dslul.openboard.inputmethod.latin;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 顶层可移动半透明悬浮窗，常驻显示输入法日志/调试输出。
 *
 * 特点（按需求）：
 *   1. 常驻：随输入法 onCreate 启动，进程不死不主动关。
 *   2. 全量打印：所有日志原样 append，不筛选。
 *   3. 保留：不清空、不覆盖，历史全部留着可复制。
 *   4. 半透明 + 可拖动：背景半透明，手指按住拖动改位置。
 *
 * 子线程（socket 线程）调用 append 时自动切主线程，线程安全。
 */
public final class ImeLogWindow {

    private static ImeLogWindow INSTANCE;

    public static ImeLogWindow get() {
        if (INSTANCE == null) {
            INSTANCE = new ImeLogWindow();
        }
        return INSTANCE;
    }

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private View rootView;
    private TextView logText;
    private ScrollView scroll;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean shown = false;

    private ImeLogWindow() {
    }

    /** 创建并显示悬浮窗（幂等，重复调用安全） */
    public void show(final Context context) {
        if (shown) return;
        final Context app = context.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);

        rootView = LayoutInflater.from(app).inflate(
                R.layout.ime_log_window, null);
        logText = rootView.findViewById(R.id.ime_log_text);
        scroll = rootView.findViewById(R.id.ime_log_scroll);

        // 半透明背景
        rootView.setBackgroundColor(0xCC000000);

        params = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        // 宽占屏幕 70%，高占 40%
        params.width = (int) (app.getResources().getDisplayMetrics().widthPixels * 0.7f);
        params.height = (int) (app.getResources().getDisplayMetrics().heightPixels * 0.4f);

        // 拖动：按住顶部区域移动
        rootView.findViewById(R.id.ime_log_drag).setOnTouchListener(new View.OnTouchListener() {
            private int lastX, lastY;
            private int downX, downY;

            @Override
            public boolean onTouch(final View v, final MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = params.x;
                        lastY = params.y;
                        downX = (int) event.getRawX();
                        downY = (int) event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = lastX + (int) (event.getRawX() - downX);
                        params.y = lastY + (int) (event.getRawY() - downY);
                        wm.updateViewLayout(rootView, params);
                        return true;
                    default:
                        return false;
                }
            }
        });

        wm.addView(rootView, params);
        shown = true;
        append("悬浮日志窗已启动（常驻/全量/可拖动/可复制）");
    }

    /** 追加一行日志（全量、保留、自动滚到底）。可在任意线程调用。 */
    public void append(final String line) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (logText == null) return;
                // 保留历史：直接追加换行，不覆盖
                logText.append(line + "\n");
                // 滚到底部显示最新
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    /** 隐藏并移除悬浮窗（一般不需调用，常驻用） */
    public void hide() {
        if (!shown || wm == null || rootView == null) return;
        wm.removeView(rootView);
        shown = false;
    }
}
