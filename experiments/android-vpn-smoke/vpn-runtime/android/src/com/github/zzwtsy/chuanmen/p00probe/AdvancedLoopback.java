package com.github.zzwtsy.chuanmen.p00probe;

import android.os.SystemClock;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** 手机上仅监听回环的合成子网端点；记录请求哈希，不保存业务报文。 */
final class AdvancedLoopback implements AutoCloseable {
    private final ServerSocket tcp = new ServerSocket();
    private final DatagramSocket udp = new DatagramSocket(null);
    private final JSONArray records = new JSONArray();
    private final Thread tcpThread, udpThread;
    private final String runId;
    private volatile boolean stopping;
    AdvancedLoopback(String runId) throws Exception {
        this.runId = runId;
        // 第二个绑定失败时也关闭已经创建的端点，避免构造失败留下实验监听。
        try {
            tcp.setReuseAddress(true); tcp.bind(new InetSocketAddress("127.0.0.1", 31281)); tcp.setSoTimeout(500);
            udp.bind(new InetSocketAddress("127.0.0.1", 31282)); udp.setSoTimeout(500);
        } catch (Exception error) { tcp.close(); udp.close(); throw error; }
        tcpThread = new Thread(new Runnable() { @Override public void run() { tcp(); } }, "p00-subnet-tcp");
        udpThread = new Thread(new Runnable() { @Override public void run() { udp(); } }, "p00-subnet-udp");
        tcpThread.start(); udpThread.start();
    }
    private synchronized boolean record(String protocol, byte[] bytes) throws Exception {
        if (!new String(bytes, StandardCharsets.UTF_8).startsWith(runId + ":subnet:") || bytes.length > 4096) return false;
        StringBuilder sha = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", x & 255));
        records.put(new JSONObject().put("protocol", protocol).put("request_sha256", sha.toString()).put("bytes", bytes.length)
            .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())); return true;
    }
    private void tcp() {
        while (!stopping) {
            try (Socket socket = tcp.accept()) {
                socket.setSoTimeout(5000); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); int value;
                while (bytes.size() <= 4096 && (value = socket.getInputStream().read()) >= 0) { bytes.write(value); if (value == 10) break; }
                byte[] data = bytes.toByteArray(); if (record("tcp", data)) socket.getOutputStream().write(data);
            } catch (java.net.SocketTimeoutException ignored) { }
            catch (Exception error) { if (!stopping) android.util.Log.e("P00Subnet", "合成 TCP 端点失败", error); }
        }
    }
    private void udp() {
        while (!stopping) {
            try {
                DatagramPacket packet = new DatagramPacket(new byte[4097], 4097); udp.receive(packet);
                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                if (record("udp", data)) udp.send(new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort()));
            } catch (java.net.SocketTimeoutException ignored) { }
            catch (Exception error) { if (!stopping) android.util.Log.e("P00Subnet", "合成 UDP 端点失败", error); }
        }
    }
    synchronized JSONArray snapshot() throws Exception { return new JSONArray(records.toString()); }
    @Override public void close() throws Exception {
        stopping = true; tcp.close(); udp.close(); tcpThread.join(6000); udpThread.join(6000);
        if (tcpThread.isAlive() || udpThread.isAlive()) throw new IllegalStateException("合成子网端点线程未释放");
    }
}
