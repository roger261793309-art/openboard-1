package org.dslul.openboard.inputmethod.latin;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.inputmethod.InputConnection;

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
 * 4. 预设来源：本机 socket 发送 SET: 内容，输入法收到后存入内存并自动注入；整串全部提交成功并自检通过后内存清空。
 * 5. 无预设内容时：按键显示空，点击无任何输入。
 *
 * 通讯：本机通过 socket 发送 SET: 指令传入预设内容（adb forward 映射到 65535），
 * 输入法收到后本地自动注入 + 自检，不再依赖文件轮询。
 */
public final class PresetEngine {

    private static final PresetEngine INSTANCE = new PresetEngine();

    // 双向通讯端口（adb forward 映射到本机 65535）
    static final int SOCKET_PORT = 65535;
    // 注入前清框后的缓冲时间（毫秒）：让删除生效、输入框稳定，再打第一个字
    private static final long PRE_CLEAR_BUFFER_MS = 300;

    private final List<String> chars = new ArrayList<>();
    private int index = 0;
    private boolean loaded = false;

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

    /** 当前按键上应显示的字符（空表示无内容/已点完） */
    public String current() {
        if (!loaded) {
            return "";
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
     * 存内存、重置计数，并立即回 "OK 收到:内容"。
     * 收到内容后主动检查输入框是否激活：
     *   - 已激活：直接开始自动注入（注入前清一次旧残留 + 缓冲）。
     *   - 未激活：等待 3 秒再次检查；仍不激活则回 "输入框未激活" 并结束本次，
     *     不再依赖系统 onInputViewShown 回调兜底启动。
     */
    public void setContent(final String content) {
        SocketServer.logEvent("[步骤] setContent 收到内容，长度=" + (content == null ? 0 : content.length()));
        chars.clear();
        index = 0;
        clickCount = 0;
        needPreClear = true; // 收到内容即记一笔：注入前需清旧残留
        lastReceived = content == null ? "" : content;
        loaded = true;
        if (!TextUtils.isEmpty(lastReceived)) {
            // 直接按字符拆分（50音/邮箱/英文统一，无罗马音）
            for (int i = 0; i < lastReceived.length(); i++) {
                chars.add(String.valueOf(lastReceived.charAt(i)));
            }
        }
        reply("OK 收到:" + lastReceived);
        SocketServer.logEvent("存入内存，字符数=" + chars.size() + "，检查输入框激活状态");
        // 收到新预设后立即通知浮层刷新显示文字（不等点击）
        requestUiRefresh();
        // 接收内容后由输入法自己算间隔、逐字注入，不再等外部点击
        checkAndStartInject();
    }

    /**
     * 收到内容后主动检查激活状态并启动注入（不依赖 onInputViewShown 回调）。
     * 必须在非主线程调用（内部可能 Thread.sleep）。
     */
    private void checkAndStartInject() {
        InputConnection ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
        if (ic != null) {
            // 第一次查就已激活：直接开始
            startAutoInject();
            return;
        }
        // 未激活：等待 3 秒后再次检查
        SocketServer.logEvent("[步骤] 输入框未激活，等待 3 秒后复查");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            // 被打断则放弃本次注入
            Thread.currentThread().interrupt();
            reply("输入框未激活");
            return;
        }
        ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
        if (ic != null) {
            // 3 秒后已激活：开始注入
            startAutoInject();
        } else {
            // 仍不激活：回 "输入框未激活" 结束本次
            SocketServer.logEvent("[步骤] 等待 3 秒后仍无激活，回 输入框未激活");
            reply("输入框未激活");
        }
    }

    /**
     * 启动自动注入：在主线程按拟人随机间隔逐字 commitText。
     * 调用前请确保输入框已激活（由 checkAndStartInject 保证）。
     * 注入前清一次旧残留，清完缓冲 300ms 再打第一个字。
     */
    private void startAutoInject() {
        if (injectHandler == null) {
            injectHandler = new Handler(Looper.getMainLooper());
        }
        injectRetry = 0;
        index = 0;
        clickCount = 0;
        // 注入前清一次旧残留（只此一处，且只一次）
        InputConnection ic = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
        if (ic != null) {
            clearInput(ic);
            needPreClear = false;
        }
        autoInjecting = true;
        SocketServer.logEvent("[步骤] startAutoInject 启动自动注入，字符数=" + chars.size());
        // 清完缓冲 300ms，让删除生效、输入框稳定，再打第一个字
        injectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!autoInjecting) return;
                scheduleNextInject();
            }
        }, PRE_CLEAR_BUFFER_MS);
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
                try {
                    String c = chars.get(index);
                    ic.commitText(c, 1);
                    index++;
                    clickCount++;
                    // 刷新浮层显示当前进度（可选，纯视觉）
                    requestUiRefresh();
                    SocketServer.logEvent("[步骤] 注入字符 index=" + index + "/" + chars.size() + " 值=[" + c + "]");
                    // 最后一个字提交完立即刹车，直接自检回完成/失败，不再约下一次提交
                    if (index >= chars.size()) {
                        InputConnection ic2 = (imeRef != null) ? imeRef.getCurrentInputConnection() : null;
                        if (ic2 != null) {
                            checkInput(ic2);
                        } else {
                            autoInjecting = false;
                        }
                        return;
                    }
                    scheduleNextInject();
                } catch (Exception e) {
                    // 注入中途 InputConnection 失效：静默清理内存、不炸进程。
                    // 注意：此处不再 reply("失败") —— 真断连时 socket 已不可达，
                    // 发了本机也收不到，且会与正常回执顺序错乱；本机靠"收不到响应=断连"判断。
                    SocketServer.logEvent("自动注入 commitText 抛异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
                    autoInjecting = false;
                    injectRetry = 0;
                    chars.clear();
                    index = 0;
                    clickCount = 0;
                    needPreClear = false;
                    loaded = false;
                }
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

    /**
     * 输入框激活时由 LatinIME 调用。
     * 注：自清旧残留与启动注入已改由 setContent -> checkAndStartInject 主动负责
     * （未激活等 3 秒再查、仍不激活回"输入框未激活"），不再依赖此回调兜底，
     * 故此处不再清框、不再启动注入，避免重复清框导致吞字。
     */
    public void onInputViewShown() {
        // 保留空壳：LatinIME 仍会调用，但不再在此处理清框/启动
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
        SocketServer.logEvent("[步骤] checkInput 进入，准备自检");
        final String filled = readBeforeCursor(ic);
        SocketServer.logEvent("[步骤] checkInput 回读完成，已填=[" + filled + "] 期望=[" + lastReceived + "]");
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
            SocketServer.logEvent("[步骤] checkInput 一致，准备 reply(完成)");
            reply("完成");
            SocketServer.logEvent("[步骤] checkInput 已 reply(完成)");
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
                // 5 次仍失败：上报脚本自愈，清理内存预输入内容，回到等待接收状态
                autoInjecting = false;
                injectRetry = 0;
                // 清理内存：清空预设、归零计数、loaded=false，回到等待新 SET:
                SocketServer.logEvent("[步骤] checkInput 重注耗尽，准备 reply(失败)");
                chars.clear();
                index = 0;
                clickCount = 0;
                needPreClear = false;
                loaded = false;
                reply("失败");
                SocketServer.logEvent("[步骤] checkInput 已 reply(失败)");
                SocketServer.logEvent("输入检查不一致 -> 已重注" + MAX_RETRY
                        + "次仍失败，回'失败'并清内存，回到等待接收状态。已填=[" + filled + "] 期望=[" + expect + "]");
            }
        }
    }

    /** 回读输入框光标前全部内容（不限左右，先把光标前能拿到的都拿到） */
    private String readBeforeCursor(final InputConnection ic) {
        if (ic == null) return "";
        try {
            CharSequence cs = ic.getTextBeforeCursor(1024, 0);
            return cs == null ? "" : cs.toString();
        } catch (Exception e) {
            // 连接失效时回读会抛异常，返回空串让调用方按"不一致"处理，避免炸进程
            SocketServer.logEvent("readBeforeCursor 抛异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
            return "";
        }
    }

    /** 自清输入框：全选 + 删除（走 InputConnection，不依赖脚本） */
    private void clearInput(final InputConnection ic) {
        if (ic == null) return;
        try {
            CharSequence cs = ic.getTextBeforeCursor(1024, 0);
            final int len = (cs == null) ? 0 : cs.length();
            SocketServer.logEvent("clearInput 执行 len=" + len);
            if (len <= 0) return;
            // 先把光标移到最前（选中全部），再删除
            ic.setSelection(0, len);
            ic.deleteSurroundingText(len, 0);
        } catch (Exception e) {
            // InputConnection 失效时调用会抛异常，静默忽略，避免炸进程
            SocketServer.logEvent("clearInput 抛异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
        }
    }

    /** 把结论发回本机（通过 SocketServer 注入的通道） */
    private void reply(final String line) {
        if (replyListener != null) {
            replyListener.onReply(line);
        }
    }
}
