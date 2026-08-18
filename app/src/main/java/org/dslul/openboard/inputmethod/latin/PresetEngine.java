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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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

    // 双向通讯端口（adb forward 映射到本机 65535）
    static final int SOCKET_PORT = 65535;

    private final List<String> chars = new ArrayList<>();
    private int index = 0;
    private long lastLoaded = 0;
    private boolean loaded = false;
    private boolean polling = false;
    private Handler pollHandler;

    // ====== 自动注入（接收内容后自己算间隔逐字提交，不再等外部点击）======
    // 主线程 Handler：commitText 必须在主线程执行
    private Handler injectHandler = null;
    // 是否正在自动注入中（期间忽略手动单键点击，避免重复提交）
    private boolean autoInjecting = false;
    // 自检失败重注次数（≤5 次自己纠，>5 才回"失败"让脚本自愈）
    private int injectRetry = 0;
    private static final int MAX_RETRY = 5;
    private final Random rand = new Random();
    // 真人填邮箱时会在这些符号上微顿（确认一下）
    private static final Set<Character> PAUSE_CHARS = new HashSet<>();
    static {
        PAUSE_CHARS.add('@');
        PAUSE_CHARS.add('.');
        PAUSE_CHARS.add('_');
        PAUSE_CHARS.add('-');
    }

    // ====== 双向通讯（socket）相关状态 ======
    // 点击计数：每点一下单键 +1，达到 chars.size() 时自动做输入检查
    private int clickCount = 0;
    // 收到预输入内容后，是否还需要在输入框激活时做一次自清（清掉旧残留）
    private boolean needPreClear = false;
    // 最近一次通过 socket 收到的原始内容（用于回执 "OK 收到:xxx"）
    private String lastReceived = "";
    // 回执发送通道：由 SocketServer 注入，把 OK/完成/失败 发回本机
    private OnReplyListener replyListener = null;
    // UI 刷新通道：收到新预设内容后通知 LatinIME 刷新浮层显示
    private OnUiRefreshListener uiRefreshListener = null;
    // 输入法服务引用：用于拿当前 InputConnection（做 commitText / 回读 / 自清）
    private InputMethodService imeRef = null;

    /** 回执监听：把输入法侧结论发回本机 Python */
    public interface OnReplyListener {
        void onReply(String line);
    }

    /** UI 刷新监听：收到新预设内容后由 LatinIME 在主线程刷新浮层文字 */
    public interface OnUiRefreshListener {
        void onUiRefresh();
    }

    private PresetEngine() {}

    public static PresetEngine get() {
        return INSTANCE;
    }

    /** 由 LatinIME 在 onCreate 注入服务引用（用于拿 InputConnection） */
    public void attachService(InputMethodService ime) {
        this.imeRef = ime;
    }

    /** 由 SocketServer 在每条连接上设置回执通道（不覆盖 imeRef） */
    public void setReplyListener(OnReplyListener listener) {
        this.replyListener = listener;
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
     * 点击一下：提交当前字符，index 前进，clickCount+1。
     * 当 clickCount 达到总长度时，自动做输入检查（getTextBeforeCursor 比对）。
     * 返回提交出去的字符（用于调试/log，实际提交走 InputConnection.commitText）。
     * 若已无字符可提交，返回空字符串且不产生任何输入。
     */
    public String tap(InputMethodService ime) {
        // 自动注入期间忽略手动点击：避免重复提交同一字符
        if (autoInjecting) {
            return "";
        }
        if (ime == null) return "";
        InputConnection ic = ime.getCurrentInputConnection();
        if (ic == null) return "";

        // 输入框刚激活时，若之前收到过内容且还没自清，先清掉旧残留再开始输入
        if (needPreClear) {
            clearInput(ic);
            needPreClear = false;
        }

        if (index >= chars.size()) {
            // 无内容可输入（未传入或已点完）
            return "";
        }
        String ch = chars.get(index);
        ic.commitText(ch, 1);
        index++;
        clickCount++;

        // 整串全部点完 -> 自动做输入检查
        if (index >= chars.size()) {
            checkInput(ic);
        }
        return ch;
    }

    /**
     * 通过 socket 接收预输入内容（替代读文件）。
     * 存内存、重置计数，并立即回 "OK 收到:内容"（自清推迟到输入框激活时做，
     * 因为 SET 到达时输入框可能还没聚焦，拿不到 InputConnection）。
     */
    public void setContent(final String content) {
        chars.clear();
        index = 0;
        clickCount = 0;
        needPreClear = true; // 等输入框激活时自清旧残留
        lastReceived = content == null ? "" : content;
        loaded = true;
        if (!TextUtils.isEmpty(lastReceived)) {
            // 直接按字符拆分（50音/邮箱/英文统一，无罗马音）
            for (int i = 0; i < lastReceived.length(); i++) {
                chars.add(String.valueOf(lastReceived.charAt(i)));
            }
        }
        reply("OK 收到:" + lastReceived);
        SocketServer.logEvent("存入内存，字符数=" + chars.size() + "，启动自动注入");
        // 收到新预设后立即通知浮层刷新显示文字（不等点击）
        requestUiRefresh();
        // 接收内容后由输入法自己算间隔、逐字注入，不再等外部点击
        startAutoInject();
    }

    /**
     * 启动自动注入：在主线程按拟人随机间隔逐字 commitText。
     * 若此时输入框还未激活（拿不到 InputConnection），先标记 needPreClear，
     * 等 onInputViewShown 激活后再真正启动（见 onInputViewShown）。
     */
    private void startAutoInject() {
        if (injectHandler == null) {
            injectHandler = new Handler(Looper.getMainLooper());
        }
        injectRetry = 0;
        index = 0;
        clickCount = 0;
        // 若输入框已激活，先自清旧残留再开始；否则留给 onInputViewShown 处理
        InputConnection ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
        if (ic != null) {
            clearInput(ic);
            needPreClear = false;
        } else {
            needPreClear = true; // 等激活时清
        }
        autoInjecting = true;
        scheduleNextInject();
    }

    /** 安排下一个字符的注入（带拟人随机间隔） */
    private void scheduleNextInject() {
        if (!autoInjecting) return;
        if (index >= chars.size()) {
            // 全部提交完：做输入检查（需在输入框激活且有连接时）
            InputConnection ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
            if (ic != null) {
                checkInput(ic);
            } else {
                // 框还没激活，等激活后再检查（onInputViewShown 里补）
                autoInjecting = false;
            }
            return;
        }
        // 取当前字符，算间隔，延迟后提交
        final char ch = chars.get(index).length() > 0 ? chars.get(index).charAt(0) : ' ';
        final long delay = nextDelay(ch);
        injectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!autoInjecting) return;
                InputConnection ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
                if (ic == null) {
                    // 输入框丢失，暂停注入，等激活后再续
                    autoInjecting = false;
                    return;
                }
                if (needPreClear) {
                    clearInput(ic);
                    needPreClear = false;
                }
                String c = chars.get(index);
                ic.commitText(c, 1);
                index++;
                clickCount++;
                // 刷新浮层显示当前进度（可选，纯视觉）
                requestUiRefresh();
                scheduleNextInject();
            }
        }, delay);
    }

    /**
     * 拟人间隔：每次实时随机，绝不复用上一次序列。
     * - 基础 80~250ms
     * - 遇到 @ . _ - 等特殊符号额外 +150~400ms（真人确认一下）
     * - 8% 概率插入一次长停顿 600~1500ms（偶尔走神）
     */
    private long nextDelay(char ch) {
        long d = 80 + rand.nextInt(171); // 80~250
        if (PAUSE_CHARS.contains(ch)) {
            d += 150 + rand.nextInt(251); // +150~400
        }
        if (rand.nextInt(100) < 8) {
            d += 600 + rand.nextInt(901); // +600~1500
        }
        return d;
    }

    /** 注册 UI 刷新监听（LatinIME 在主线程刷浮层） */
    public void setUiRefreshListener(final OnUiRefreshListener l) {
        uiRefreshListener = l;
    }

    /** 请求刷新浮层显示 */
    private void requestUiRefresh() {
        if (uiRefreshListener != null) {
            uiRefreshListener.onUiRefresh();
        }
    }

    /** 输入框激活时由 LatinIME 调用：若需要自清则清掉旧残留；若已收到内容但未注入则启动 */
    public void onInputViewShown() {
        InputConnection ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
        if (ic == null) return; // 还没活跃连接，等下次
        if (needPreClear) {
            clearInput(ic);
            needPreClear = false;
            SocketServer.logEvent("输入框激活，已执行自清旧残留");
        }
        // 已收到内容、但之前因框未激活没启动注入，则现在启动
        if (autoInjecting && index < chars.size() && !injectScheduled()) {
            scheduleNextInject();
        } else if (!autoInjecting && loaded && index == 0 && !chars.isEmpty()
                && TextUtils.equals(current(), chars.get(0))) {
            // SET 先到、框后激活：重新启动自动注入
            startAutoInject();
        }
    }

    /** 简易判断：当前是否已有待执行的注入任务（避免重复调度） */
    private boolean injectScheduled() {
        // 主线程 Handler 的队列无法直接查询，这里用 autoInjecting + index 进度近似判断：
        // 只要 autoInjecting 且 index 还没到末尾，就认为任务在跑，不重复 schedule
        return autoInjecting;
    }

    /**
     * 自动输入检查：回读输入框已填内容，与预设比对。
     * 一致 -> 回 "完成" 并清除内存预设；不一致 -> 自清 + 回 "失败"（保留内存预设等重填）。
     */
    private void checkInput(final InputConnection ic) {
        final String filled = readBeforeCursor(ic);
        final String expect = lastReceived;
        if (filled != null && filled.equals(expect)) {
            // 输入正确：清内存预设，回到空状态
            chars.clear();
            index = 0;
            clickCount = 0;
            needPreClear = false;
            loaded = false;
            autoInjecting = false;
            injectRetry = 0;
            reply("完成");
            SocketServer.logEvent("输入检查一致 -> 完成，内存预设已清。已填=[" + filled + "]");
        } else {
            // 输入错误：自清 + 重新注入（间隔全新随机），≤5 次自己纠，>5 才回失败
            injectRetry++;
            clearInput(ic);
            clickCount = 0;
            index = 0;
            if (injectRetry <= MAX_RETRY) {
                SocketServer.logEvent("输入检查不一致 -> 第" + injectRetry
                        + "次重注（间隔重新随机）。已填=[" + filled + "] 期望=[" + expect + "]");
                // 重新启动自动注入（nextDelay 每次实时随机，不会与上轮相同）
                autoInjecting = true;
                scheduleNextInject();
            } else {
                // 5 次仍失败：上报脚本自愈，清空重试计数与注入状态
                autoInjecting = false;
                injectRetry = 0;
                reply("失败");
                SocketServer.logEvent("输入检查不一致 -> 已重注" + MAX_RETRY
                        + "次仍失败，回'失败'交脚本处理。已填=[" + filled + "] 期望=[" + expect + "]");
            }
        }
    }

    /** 回读输入框光标前全部内容（不限左右，先把光标前能拿到的都拿到） */
    private String readBeforeCursor(final InputConnection ic) {
        if (ic == null) return "";
        CharSequence cs = ic.getTextBeforeCursor(1024, 0);
        return cs == null ? "" : cs.toString();
    }

    /** 自清输入框：全选 + 删除（走 InputConnection，不依赖脚本） */
    private void clearInput(final InputConnection ic) {
        if (ic == null) return;
        CharSequence cs = ic.getTextBeforeCursor(1024, 0);
        final int len = (cs == null) ? 0 : cs.length();
        if (len <= 0) return;
        // 先把光标移到最前（选中全部），再删除
        ic.setSelection(0, len);
        ic.deleteSurroundingText(len, 0);
    }

    /** 把结论发回本机（通过 SocketServer 注入的通道） */
    private void reply(final String line) {
        if (replyListener != null) {
            replyListener.onReply(line);
        }
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
