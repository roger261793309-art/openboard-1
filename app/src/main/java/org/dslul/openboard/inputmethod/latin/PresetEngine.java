package org.dslul.openboard.inputmethod.latin;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 预设输入引擎（卧底输入法核心逻辑）
 *
 * 设计原则（用户硬性要求）：
 * 1. 不动底层 InputMethodService / InputConnection 握手协议，只用原生 commitText。
 * 2. 反推 = 直接把目标串按字符拆分（日文/邮箱/英文统一，无罗马音中间态）。
 * 3. 单键点击 = 提交当前字符一个，按键显示递进；末点确认上屏。
 * 4. 预设文件：读入内存后文件原样保留；整串全部点完（最后一个字符也提交成功）后才清空文件。
 * 5. 文件为空/不存在时：按键显示空，点击无任何输入。
 *
 * 通讯：本机 adb push 预设内容到文件，输入法轮询读取。
 * 默认路径为本应用私有外部存储（跨安卓版本可读写，adb 也能 push）：
 *   /sdcard/Android/data/org.dslul.openboard.inputmethod.latin/files/ime_in.txt
 * 回退路径（老系统或已授权的情况）：/sdcard/ime_in.txt
 */
public final class PresetEngine {

    private static final PresetEngine INSTANCE = new PresetEngine();

    // 默认预设文件路径（应用私有外部存储目录）
    private static final String PKG = "org.dslul.openboard.inputmethod.latin";
    private static final String PRESET_REL = "ime_in.txt";
    private static final String DONE_REL = "ime_done.txt";

    private final List<String> chars = new ArrayList<>();
    private int index = 0;
    private long lastLoaded = 0;
    private boolean loaded = false;
    private boolean polling = false;
    private Handler pollHandler;

    private PresetEngine() {}

    public static PresetEngine get() {
        return INSTANCE;
    }

    /**
     * 启动轮询线程（在 onStartInputView 中调用一次即可）。
     * 每隔 500ms 检查预设文件是否有新内容；有则重新加载到内存。
     */
    public void startPolling() {
        if (polling) return;
        polling = true;
        pollHandler = new Handler(Looper.getMainLooper());
        pollHandler.postDelayed(pollRunnable, 500);
    }

    public void stopPolling() {
        polling = false;
        if (pollHandler != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            checkReload();
            pollHandler.postDelayed(this, 500);
        }
    };

    /** 检查预设文件是否变化，变化则重新加载 */
    private void checkReload() {
        File f = presetFile();
        if (f == null) return;
        long mod = f.lastModified();
        if (f.exists() && mod != lastLoaded) {
            loadFromFile(f);
            lastLoaded = mod;
        }
    }

    /** 从文件加载预设内容到内存（原文件不删除） */
    private void loadFromFile(File f) {
        try {
            String content = readFile(f);
            chars.clear();
            index = 0;
            if (!TextUtils.isEmpty(content)) {
                // 直接按字符拆分（50音/邮箱/英文统一，无罗马音）
                for (int i = 0; i < content.length(); i++) {
                    chars.add(String.valueOf(content.charAt(i)));
                }
            }
            loaded = true;
        } catch (IOException e) {
            // 读取失败：保持空状态
            chars.clear();
            index = 0;
        }
    }

    /** 当前按键上应显示的字符（空表示无内容/已点完） */
    public String current() {
        if (!loaded) {
            checkReload();
        }
        if (index < chars.size()) {
            return chars.get(index);
        }
        return "";
    }

    /**
     * 点击一下：提交当前字符，index 前进。
     * 返回提交出去的字符（用于调试/log，实际提交走 InputConnection.commitText）。
     * 若已无字符可提交，返回空字符串且不产生任何输入。
     */
    public String tap(InputMethodService ime) {
        if (ime == null) return "";
        InputConnection ic = ime.getCurrentInputConnection();
        if (ic == null) return "";

        if (index >= chars.size()) {
            // 无内容可输入（文件空或已点完）
            return "";
        }
        String ch = chars.get(index);
        ic.commitText(ch, 1);
        index++;

        // 整串全部点完 -> 清空预设文件，回到空状态
        if (index >= chars.size()) {
            clearPreset();
            writeDone();
            chars.clear();
            index = 0;
            loaded = false; // 下次进入重新检查
        }
        return ch;
    }

    // ====== 文件操作 ======

    private File presetFile() {
        // 优先应用私有外部存储
        File ext = new File("/sdcard/Android/data/" + PKG + "/files");
        if (ext.exists() || ext.mkdirs()) {
            return new File(ext, PRESET_REL);
        }
        // 回退：/sdcard 根目录
        return new File("/sdcard", PRESET_REL);
    }

    private File doneFile() {
        File ext = new File("/sdcard/Android/data/" + PKG + "/files");
        if (ext.exists() || ext.mkdirs()) {
            return new File(ext, DONE_REL);
        }
        return new File("/sdcard", DONE_REL);
    }

    private String readFile(File f) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
        fis.close();
        // 去掉可能的换行符，避免把换行当字符输入
        String s = bos.toString("UTF-8");
        return s.replace("\r", "").replace("\n", "");
    }

    /** 清空预设文件（只清 ime_in.txt 内容，不动 ime_done.txt 之外的逻辑） */
    private void clearPreset() {
        File f = presetFile();
        try {
            FileWriter fw = new FileWriter(f, false);
            fw.write("");
            fw.flush();
            fw.close();
            lastLoaded = f.lastModified();
        } catch (IOException ignored) {
            // 清空失败不致命，下次轮询会再试
        }
    }

    private void writeDone() {
        File f = doneFile();
        try {
            FileWriter fw = new FileWriter(f, false);
            fw.write("done");
            fw.flush();
            fw.close();
        } catch (IOException ignored) {
        }
    }
}
