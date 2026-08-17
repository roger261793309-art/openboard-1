package org.dslul.openboard.inputmethod.latin;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 双向通讯服务（卧底输入法）。
 *
 * 不走 INTERNET 权限：端口由 adb reverse 映射到本机，数据走本地回环，
 * 符合清单硬约束（永不声明 INTERNET 权限）。
 *
 * 协议（纯文本行，\n 分隔）：
 *   本机 -> 输入法：  SET:预输入内容
 *   输入法 -> 本机：  OK 收到:预输入内容      （传上且已准备，可开始输入）
 *                   完成                    （全部点完且回读校验一致，内存已清）
 *                   失败                    （回读校验不一致，已自清，等脚本重填）
 *
 * 启动位置：LatinIME.onCreate()（输入法进程常驻即生效，不依赖点输入框）。
 */
public final class SocketServer {

    private static final String TAG = "SocketServer";
    private static SocketServer INSTANCE;

    private ServerSocket serverSocket;
    private boolean running = false;
    private final Thread acceptThread;

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
    }

    private final class AcceptRunnable implements Runnable {
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(PresetEngine.SOCKET_PORT);
            } catch (IOException e) {
                Log.e(TAG, "ServerSocket 绑定失败: " + e.getMessage());
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
                    Log.e(TAG, "accept 出错: " + e.getMessage());
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
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStreamWriter out = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8)) {

                String line;
                while ((line = in.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("SET:")) {
                        final String content = trimmed.substring(4);
                        // 把回执通道接到这条连接上，输入法侧的 OK/完成/失败 发回本机
                        PresetEngine.get().setReplyListener(
                                new PresetEngine.OnReplyListener() {
                                    @Override
                                    public void onReply(final String replyLine) {
                                        try {
                                            out.write(replyLine + "\n");
                                            out.flush();
                                        } catch (IOException e) {
                                            Log.e(TAG, "回执发送失败: " + e.getMessage());
                                        }
                                    }
                                });
                        PresetEngine.get().setContent(content);
                    }
                    // 其它指令可在此扩展
                }
            } catch (IOException e) {
                Log.e(TAG, "客户端连接处理出错: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
