package org.dslul.openboard.inputmethod.latin;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 双向通讯服务（卧底输入法）。
 *
 * 端口由 adb forward 映射到本机，输入法侧 ServerSocket 绑定本地端口需 INTERNET 权限
 * （已在 AndroidManifest 声明）。
 *
 * 协议（纯文本行，\n 分隔）：
 *   本机 -> 输入法：  SET:预输入内容
 *   输入法 -> 本机：  OK 收到:预输入内容      （传上且已准备，可开始输入）
 *                   完成                    （全部点完且回读校验一致，内存已清）
 *                   失败                    （回读校验不一致，已自清，等脚本重填）
 *
 * 启动位置：LatinIME.onCreate()（输入法进程常驻即生效，不依赖点输入框）。
 *
 * 端口流程输入记录：写入应用私有外部存储，无需额外权限，随时可查。
 *   路径：/sdcard/Android/data/org.dslul.openboard.inputmethod.latin/files/ime_socket_log.txt
 */
public final class SocketServer {

    /** 端口流程输入记录文件（应用内部存储，进程必可写；原 sdcard/Android/data 路径在云机建不出来） */
    private static final String IME_LOG_PATH =
            "/data/data/org.dslul.openboard.inputmethod.latin/files/ime_socket_log.txt";

    /** 追加一条带时间戳的记录到输入记录文件（失败静默，绝不影响主流程） */
    public static void logEvent(final String msg) {
        try {
            File f = new File(IME_LOG_PATH);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            try (FileWriter fw = new FileWriter(f, true)) {
                fw.write("[" + ts + "] " + msg + "\n");
            }
        } catch (IOException ignored) {
            // 记录失败不影响输入法/端口主流程
        }
    }

    private static final String TAG = "SocketServer";
    private static SocketServer INSTANCE;

    private ServerSocket serverSocket;
    private boolean running = false;
    private final Thread acceptThread;

    // 当前活跃长连接的输出通道（连上就设置，断开才清空）。
    // 回执统一写这里，保证多条 SET 共用同一条长连接，不绑死匿名内部类的 out。
    private OutputStreamWriter activeOut = null;

    // 回执写专用单线程：保证网络写永远不在主线程执行，规避 StrictMode 主线程联网限制
    private final ExecutorService replyExecutor = Executors.newSingleThreadExecutor();

    private SocketServer() {
        acceptThread = new Thread(new AcceptRunnable(), "PresetSocketAccept");
        acceptThread.setDaemon(true);
    }

    public static SocketServer get() {
        if (INSTANCE == null) {
            INSTANCE = new SocketServer();
        }
        return INSTANCE;
    }

    /** 启动监听（幂等，重复调用安全） */
    public void start() {
        if (running) return;
        running = true;
        acceptThread.start();
        Log.i(TAG, "SocketServer 启动，端口=" + PresetEngine.SOCKET_PORT);
        logEvent("SocketServer 启动，端口=" + PresetEngine.SOCKET_PORT);
    }

    private final class AcceptRunnable implements Runnable {
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(PresetEngine.SOCKET_PORT);
                logEvent("ServerSocket 绑定成功，开始监听端口=" + PresetEngine.SOCKET_PORT);
            } catch (IOException e) {
                Log.e(TAG, "ServerSocket 绑定失败: " + e.getMessage());
                logEvent("ServerSocket 绑定异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
                running = false;
                return;
            }
            while (running) {
                try {
                    final Socket client = serverSocket.accept();
                    // 每个连接起一个线程处理（测试场景并发极低）
                    new Thread(new ClientRunnable(client), "PresetSocketClient").start();
                } catch (IOException e) {
                    if (!running) break;
                    Log.e(TAG, "accept 异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
                }
            }
        }
    }

    private final class ClientRunnable implements Runnable {
        private final Socket socket;

        ClientRunnable(final Socket s) {
            this.socket = s;
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStreamWriter out = new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8);
                // 连接建立即登记为当前活跃长连接通道，回执统一走这里
                activeOut = out;
                // 把回执通道接到这条长连接上，输入法侧的 OK/完成/失败 发回本机
                PresetEngine.get().setReplyListener(
                        new PresetEngine.OnReplyListener() {
                            @Override
                            public void onReply(final String replyLine) {
                                logEvent("回执 -> 本机: " + replyLine);
                                final OutputStreamWriter w = activeOut;
                                if (w == null) return;
                                // 网络写交专用单线程执行，避免在主线程联网被 StrictMode 拦截
                                replyExecutor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            w.write(replyLine + "\n");
                                            w.flush();
                                        } catch (IOException e) {
                                            Log.e(TAG, "回执发送异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
                                            logEvent("回执发送异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
                                        }
                                    }
                                });
                            }
                        });

                String line;
                // 长连接：连上就不断开，循环接收 SET，直到流结束（对端真断开）才退出
                while ((line = in.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("SET:")) {
                        final String content = trimmed.substring(4);
                        logEvent("收到 SET，预输入内容=[" + content + "]");
                        PresetEngine.get().setContent(content);
                    }
                    // 其它指令可在此扩展
                }
            } catch (IOException e) {
                Log.e(TAG, "客户端连接处理异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
                logEvent("客户端连接处理异常 原文=[" + e + "] 来源=[" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "无栈") + "]");
            } finally {
                // 仅当这条就是当前活跃连接时才清空，绝不在正常流程中主动关闭 socket
                if (activeOut != null) {
                    try { activeOut.flush(); } catch (IOException ignored) {}
                    activeOut = null;
                }
                PresetEngine.get().setReplyListener(null);
                // 注意：不调用 socket.close()，由进程退出或 accept 循环停止时统一回收
            }
        }
    }
}
