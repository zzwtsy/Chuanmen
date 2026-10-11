package com.github.zzwtsy.chuanmen.p00probe;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 独立工作线程在 USB/无线 ADB 断开后继续测量，只访问本进程与设备电量计。 */
final class PerformanceExperiment implements ServiceConnection {
    private final BoundaryActivity activity;
    private final String host, runId;
    private final int idleSeconds;
    private final CountDownLatch connected = new CountDownLatch(1);
    private BoundaryVpnService service;
    private PerformanceExperiment(BoundaryActivity activity, String host, String runId, int idleSeconds) {
        this.activity=activity; this.host=host; this.runId=runId; this.idleSeconds=idleSeconds;
    }
    static void start(BoundaryActivity activity,String host,String runId,int idleSeconds) {
        if (host==null || !host.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}") || runId==null
                || !runId.matches("p00-[0-9a-f]{12}") || idleSeconds<300 || idleSeconds>3600)
            throw new IllegalStateException("性能探针参数无效");
        if (new File(activity.getFilesDir(),"p00-performance-"+runId+".json").exists()) throw new IllegalStateException("禁止覆盖同轮性能实验");
        PerformanceExperiment worker=new PerformanceExperiment(activity,host,runId,idleSeconds);
        activity.startProbe();
        if (!activity.bindService(new Intent(activity,BoundaryVpnService.class),worker,Context.BIND_AUTO_CREATE)) throw new IllegalStateException("性能服务未绑定");
        new Thread(new Runnable() { @Override public void run() { worker.run(); } },"p00-performance-owner").start();
    }
    @Override public void onServiceConnected(ComponentName name,IBinder binder) { service=((BoundaryVpnService.LocalBinder)binder).service(); connected.countDown(); }
    @Override public void onServiceDisconnected(ComponentName name) { }
    private void save(JSONObject report) throws Exception {
        File temporary=new File(activity.getFilesDir(),"p00-performance-"+runId+".tmp");
        try (FileOutputStream stream=new FileOutputStream(temporary)) { stream.write(report.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!temporary.renameTo(new File(activity.getFilesDir(),"p00-performance-"+runId+".json"))) throw new IllegalStateException("性能检查点写入失败");
    }
    private JSONObject sample() throws Exception {
        BatteryManager manager=activity.getSystemService(BatteryManager.class);
        Intent battery=activity.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int charge=manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long energy=manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
        return new JSONObject().put("elapsed_ns",SystemClock.elapsedRealtimeNanos())
            .put("process_cpu_ms",android.os.Process.getElapsedCpuTime()).put("process_pss_kib",Debug.getPss())
            .put("charge_uah",charge==Integer.MIN_VALUE?JSONObject.NULL:charge)
            .put("energy_nwh",energy==Long.MIN_VALUE?JSONObject.NULL:energy)
            .put("plugged",battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1))
            .put("voltage_mv",battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1))
            .put("temperature_tenths_c",battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1))
            .put("screen_interactive",activity.getSystemService(PowerManager.class).isInteractive());
    }
    private String digest(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(); for(byte x:bytes) result.append(String.format("%02x",x&255)); return result.toString();
    }
    private void ready() throws Exception {
        ConnectivityManager manager=activity.getSystemService(ConnectivityManager.class); long deadline=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline) {
            Network active=manager.getActiveNetwork(); NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);
            JSONObject info=NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)).getJSONObject("map").optJSONObject("p00-performance");
            if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && info!=null) {
                JSONArray routes=info.optJSONArray("routes");
                if(routes!=null) for(int i=0;i<routes.length();i++) {
                    JSONObject addr=routes.getJSONObject(i).optJSONObject("ipv4_addr");
                    if(addr!=null && addr.getJSONObject("address").getLong("addr")==182321153L) return;
                }
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("真实性能 VPN 路由未就绪");
    }
    private JSONObject metrics(JSONObject first,JSONObject last,JSONArray samples) throws Exception {
        double seconds=(last.getLong("elapsed_ns")-first.getLong("elapsed_ns"))/1e9;
        return new JSONObject().put("duration_seconds",seconds).put("cpu_one_core_percent",100.0*(last.getLong("process_cpu_ms")-first.getLong("process_cpu_ms"))/(seconds*1000))
            .put("pss_kib_start",first.getLong("process_pss_kib")).put("pss_kib_end",last.getLong("process_pss_kib")).put("samples",samples);
    }
    // 条件：真实虚拟地址和核心路由；操作：60 秒串行 64 KiB TCP 块回显；结果：逐块内容、累计哈希和实际耗时。
    private JSONObject tcp() throws Exception {
        byte[] block=new byte[65536]; for(int i=0;i<block.length;i++) block[i]=(byte)(i%251);
        byte[] hello=(runId+":perf:tcp\n").getBytes(StandardCharsets.UTF_8),response=new byte[block.length];
        MessageDigest sent=MessageDigest.getInstance("SHA-256"),received=MessageDigest.getInstance("SHA-256");
        JSONObject first=sample(); JSONArray samples=new JSONArray().put(first); long frames=0,nextSample=SystemClock.elapsedRealtime()+1000;
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("10.222.0.1",31274),5000); socket.setSoTimeout(10000);
            if(!"10.222.0.2".equals(socket.getLocalAddress().getHostAddress())) throw new IllegalStateException("TCP 性能 socket 未使用真实 TUN 地址");
            DataOutputStream output=new DataOutputStream(socket.getOutputStream()); DataInputStream input=new DataInputStream(socket.getInputStream());
            output.write(hello); byte[] welcome=new byte[hello.length]; input.readFully(welcome);
            if(!Arrays.equals(hello,welcome)) throw new IllegalStateException("TCP 性能握手不符");
            long deadline=SystemClock.elapsedRealtime()+60000;
            do {
                output.writeInt(block.length); output.write(block); input.readFully(response);
                if(!Arrays.equals(block,response)) throw new IllegalStateException("TCP 性能块回包不符");
                sent.update(block); received.update(response); frames++;
                if(SystemClock.elapsedRealtime()>=nextSample) { samples.put(sample()); nextSample=SystemClock.elapsedRealtime()+1000; }
            } while(SystemClock.elapsedRealtime()<deadline);
            output.writeInt(0);
        }
        JSONObject last=sample(); samples.put(last); JSONObject result=metrics(first,last,samples);
        return result.put("protocol","tcp").put("frames",frames).put("bytes",frames*block.length)
            .put("request_sha256",hex(sent.digest())).put("response_sha256",hex(received.digest()))
            .put("goodput_mbps",frames*block.length*8.0/result.getDouble("duration_seconds")/1000000).put("source_ip","10.222.0.2");
    }
    // 条件：单个 1024 字节 UDP 数据报；操作：60 秒串行真实回显；结果：无重试、每条匹配及实际累计哈希。
    private JSONObject udp() throws Exception {
        byte[] prefix=(runId+":perf:udp:").getBytes(StandardCharsets.UTF_8),data=new byte[1024],buffer=new byte[2048];
        System.arraycopy(prefix,0,data,0,prefix.length);
        MessageDigest sent=MessageDigest.getInstance("SHA-256"),received=MessageDigest.getInstance("SHA-256");
        JSONObject first=sample(); JSONArray samples=new JSONArray().put(first); long frames=0,nextSample=SystemClock.elapsedRealtime()+1000;
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.connect(InetAddress.getByName("10.222.0.1"),31275); socket.setSoTimeout(10000);
            if(!"10.222.0.2".equals(socket.getLocalAddress().getHostAddress())) throw new IllegalStateException("UDP 性能 socket 未使用真实 TUN 地址");
            long deadline=SystemClock.elapsedRealtime()+60000;
            do {
                java.nio.ByteBuffer.wrap(data,prefix.length,8).putLong(frames);
                socket.send(new DatagramPacket(data,data.length)); DatagramPacket packet=new DatagramPacket(buffer,buffer.length); socket.receive(packet);
                if(packet.getLength()!=data.length || !Arrays.equals(data,Arrays.copyOfRange(buffer,packet.getOffset(),packet.getOffset()+packet.getLength()))) throw new IllegalStateException("UDP 性能回包不符");
                sent.update(data); received.update(buffer,packet.getOffset(),packet.getLength()); frames++;
                if(SystemClock.elapsedRealtime()>=nextSample) { samples.put(sample()); nextSample=SystemClock.elapsedRealtime()+1000; }
            } while(SystemClock.elapsedRealtime()<deadline);
            byte[] end=(runId+":perf:udp:end\n").getBytes(StandardCharsets.UTF_8); socket.send(new DatagramPacket(end,end.length));
            DatagramPacket packet=new DatagramPacket(buffer,buffer.length); socket.receive(packet);
            if(!Arrays.equals(end,Arrays.copyOfRange(buffer,packet.getOffset(),packet.getOffset()+packet.getLength()))) throw new IllegalStateException("UDP 性能结束回执不符");
        }
        JSONObject last=sample(); samples.put(last); JSONObject result=metrics(first,last,samples);
        return result.put("protocol","udp").put("frames",frames).put("bytes",frames*data.length)
            .put("request_sha256",hex(sent.digest())).put("response_sha256",hex(received.digest()))
            .put("goodput_mbps",frames*data.length*8.0/result.getDouble("duration_seconds")/1000000).put("source_ip","10.222.0.2");
    }
    private void run() {
        JSONObject report=new JSONObject();
        try {
            report.put("layer","android_native_performance").put("status","starting").put("run_id",runId)
                .put("core_commit","7592951b808c4839cb9d77cc4bc642f124915951").put("measurement_owner","experiment_process_including_harness")
                .put("idle_seconds_requested",idleSeconds);
            if(!connected.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("性能服务连接超时");
            P00NativeBridge.resetObservations(); long started=SystemClock.elapsedRealtimeNanos();
            service.startCore("p00-performance",DatapathInstrumentation.configuration(host).replace("p00-datapath","p00-performance"),host); ready();
            byte[] request=(runId+":perf:connect\n").getBytes(StandardCharsets.UTF_8);
            try(Socket socket=new Socket()) {
                socket.connect(new InetSocketAddress("10.222.0.1",31271),5000); socket.setSoTimeout(5000); socket.getOutputStream().write(request);
                byte[] response=new byte[request.length]; new DataInputStream(socket.getInputStream()).readFully(response);
                if(!Arrays.equals(request,response)) throw new IllegalStateException("连接计时实际回包失败");
            }
            report.put("connect_ms",(SystemClock.elapsedRealtimeNanos()-started)/1e6).put("connect_request_sha256",digest(request))
                .put("status","measuring_tcp"); save(report);
            report.put("tcp",tcp()).put("status","measuring_udp"); save(report);
            report.put("udp",udp()).put("status","waiting_unplug_and_screen_off"); save(report);
            // 充电或亮屏时不生成空闲耗电数值；等待真实电量服务与屏幕状态，不由宿主伪造属性。
            long deadline=SystemClock.elapsedRealtime()+300000; JSONObject first=sample();
            while((first.getInt("plugged")!=0 || first.getBoolean("screen_interactive")) && SystemClock.elapsedRealtime()<deadline) { SystemClock.sleep(1000); first=sample(); }
            if(first.getInt("plugged")!=0 || first.getBoolean("screen_interactive")) throw new IllegalStateException("未满足真实拔电与灭屏空闲测量条件");
            JSONArray samples=new JSONArray().put(first); report.put("status","measuring_idle").put("idle_samples",samples); save(report);
            deadline=SystemClock.elapsedRealtime()+idleSeconds*1000L;
            while(SystemClock.elapsedRealtime()<deadline) {
                SystemClock.sleep(Math.min(30000,Math.max(1,deadline-SystemClock.elapsedRealtime()))); samples.put(sample()); save(report);
            }
            JSONObject last=samples.getJSONObject(samples.length()-1),idle=metrics(first,last,samples); boolean stable=true;
            for(int i=0;i<samples.length();i++) stable &= samples.getJSONObject(i).getInt("plugged")==0 && !samples.getJSONObject(i).getBoolean("screen_interactive");
            idle.put("conditions_stable",stable).put("energy_scope","whole_device_counter_including_wifi_and_other_background_work");
            if(stable && !first.isNull("energy_nwh") && !last.isNull("energy_nwh") && first.getLong("energy_nwh")>=last.getLong("energy_nwh"))
                idle.put("energy_mwh",(first.getLong("energy_nwh")-last.getLong("energy_nwh"))/1000000.0).put("energy_method","BatteryManager energy counter");
            else if(stable && !first.isNull("charge_uah") && !last.isNull("charge_uah") && first.getLong("charge_uah")>last.getLong("charge_uah") && first.getInt("voltage_mv")>0 && last.getInt("voltage_mv")>0)
                idle.put("energy_mwh_estimate",(first.getLong("charge_uah")-last.getLong("charge_uah"))*(first.getInt("voltage_mv")+last.getInt("voltage_mv"))/2000000.0).put("energy_method","charge_delta_times_endpoint_mean_voltage_estimate");
            else idle.put("energy_status","unverified").put("energy_reason","计数器不支持、无可分辨下降或条件变化");
            report.put("idle",idle).remove("idle_samples");
            report.put("network_infos",NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16))).put("observations",new JSONObject(P00NativeBridge.observations()))
                .put("closed",service.stopCore()).put("instances_after",new JSONObject(EasyTierJNI.listInstances(16))).put("status","passed");
        } catch(Throwable error) {
            try { report.put("status","failed").put("error",error.toString()); if(service!=null) report.put("failure_closed",service.stopCore()); }
            catch(Exception cleanup) { try { report.put("cleanup_error",cleanup.toString()); } catch(Exception ignored) { } }
        } finally {
            activity.runOnUiThread(new Runnable() { @Override public void run() { activity.unbindService(PerformanceExperiment.this); activity.stopService(new Intent(activity,BoundaryVpnService.class)); } });
            try {
                boolean destroyed=BoundaryVpnService.destroyed.await(30,TimeUnit.SECONDS); int notifications=activity.getSystemService(NotificationManager.class).getActiveNotifications().length;
                report.put("service_destroyed",destroyed).put("own_active_notifications_after",notifications).put("cleanup_error",BoundaryVpnService.cleanupError==null?JSONObject.NULL:BoundaryVpnService.cleanupError);
                if(!destroyed || notifications!=0 || BoundaryVpnService.cleanupError!=null) report.put("status","failed"); save(report);
            } catch(Exception error) { android.util.Log.e("P00Performance","性能结果保存失败",error); }
        }
    }
}
