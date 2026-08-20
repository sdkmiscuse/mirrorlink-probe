package com.rcd330.mirrorlink;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView tvLog, tvStatus;
    private ScrollView scrollView;
    private ExecutorService executor;
    private Handler mainHandler;
    private StringBuilder logBuilder = new StringBuilder();

    private static final String UPNP_MULTICAST_ADDR = "239.255.255.250";
    private static final int UPNP_PORT = 1900;
    private static final String MIRRORLINK_DEVICE_TYPE = "urn:mirrorlink-org:device:CCS:1";

    private static final String MSEARCH_MESSAGE =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: " + MIRRORLINK_DEVICE_TYPE + "\r\n" +
            "\r\n";

    private static final String MSEARCH_ALL =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: upnp:rootdevice\r\n" +
            "\r\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        scrollView = findViewById(R.id.scrollView);
        executor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());

        Button btnProbe = findViewById(R.id.btnProbe);
        Button btnClear = findViewById(R.id.btnClear);

        btnProbe.setOnClickListener(v -> startProbe());
        btnClear.setOnClickListener(v -> clearLog());

        log("App started. Connect phone to RCD330 via USB then tap Start.");
        log("Phone IP addresses detected:");
        listNetworkInterfaces();
    }

    private void startProbe() {
        setStatus("Probing...");
        log("\n--- Starting MirrorLink Discovery ---");
        log("Time: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        executor.execute(this::runUPnPDiscovery);
        executor.execute(this::scanCommonMirrorLinkPorts);
    }

    private void runUPnPDiscovery() {
        log("\n[UPnP] Sending M-SEARCH for MirrorLink CCS device...");
        sendMSearch(MSEARCH_MESSAGE, "MirrorLink CCS");
        log("\n[UPnP] Sending M-SEARCH for all UPnP root devices...");
        sendMSearch(MSEARCH_ALL, "UPnP rootdevice");
    }

    private void sendMSearch(String message, String label) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(5000);
            InetAddress group = InetAddress.getByName(UPNP_MULTICAST_ADDR);
            byte[] data = message.getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(data, data.length, group, UPNP_PORT);
            for (int i = 0; i < 3; i++) {
                socket.send(packet);
                log("[UPnP] M-SEARCH sent (" + (i+1) + "/3) for " + label);
                Thread.sleep(100);
            }
            log("[UPnP] Listening for responses (5 seconds)...");
            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket response = new DatagramPacket(buf, buf.length);
                    socket.receive(response);
                    String responseStr = new String(response.getData(), 0, response.getLength());
                    log("\n[UPnP] *** RESPONSE from " + response.getAddress().getHostAddress() + " ***");
                    log(responseStr);
                } catch (Exception e) { }
            }
            log("[UPnP] Discovery done for " + label);
            socket.close();
        } catch (Exception e) {
            log("[UPnP] Error: " + e.getMessage());
        }
    }

    private void scanCommonMirrorLinkPorts() {
        log("\n[Scan] Scanning common MirrorLink ports...");
        String[] baseIPs = {"192.168.42.", "192.168.48.", "169.254.1.", "10.0.0."};
        int[] ports = {1900, 8888, 8889, 554, 7777, 80, 8080};
        for (String base : baseIPs) {
            for (int host = 1; host <= 5; host++) {
                String ip = base + host;
                for (int port : ports) {
                    checkPort(ip, port);
                }
            }
        }
        log("\n[Scan] Complete.");
        setStatus("Probe complete. Check log.");
    }

    private void checkPort(String ip, int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), 300);
            log("[Scan] *** OPEN: " + ip + ":" + port + " ***");
            if (port == 80 || port == 8080) {
                fetchDeviceDescription(socket, ip, port);
            } else {
                socket.setSoTimeout(1000);
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    int lines = 0;
                    while ((line = reader.readLine()) != null && lines < 10) {
                        response.append(line).append("\n");
                        lines++;
                    }
                    if (response.length() > 0) log("[Scan] Banner: " + response);
                } catch (Exception e) { }
            }
            socket.close();
        } catch (Exception e) { }
    }

    private void fetchDeviceDescription(Socket socket, String ip, int port) {
        try {
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println("GET /description.xml HTTP/1.0");
            writer.println("Host: " + ip + ":" + port);
            writer.println("Connection: close");
            writer.println();
            socket.setSoTimeout(2000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 30) {
                response.append(line).append("\n");
                lines++;
            }
            log("[HTTP] " + ip + ":" + port + ":\n" + response);
        } catch (Exception e) {
            log("[HTTP] No description at " + ip + ":" + port);
        }
    }

    private void listNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    log("  " + iface.getName() + ": " + addr.getHostAddress());
                }
            }
        } catch (Exception e) {
            log("  Error: " + e.getMessage());
        }
    }

    private void log(String message) {
        mainHandler.post(() -> {
            logBuilder.append(message).append("\n");
            tvLog.setText(logBuilder.toString());
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void setStatus(String status) {
        mainHandler.post(() -> tvStatus.setText("Status: " + status));
    }

    private void clearLog() {
        logBuilder = new StringBuilder();
        tvLog.setText("");
        setStatus("Ready");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
