package com.dosgo.gateway;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dosgo.gateway.net.Logs;
import com.dosgo.gateway.net.NetUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.List;

public class MainActivity extends Activity {

    private static final int BG = 0xFF0B0F14;
    private static final int CARD = 0xFF161B22;
    private static final int BORDER = 0xFF30363D;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF8B949E;
    private static final int ACCENT = 0xFF1F6FEB;
    private static final int GREEN = 0xFF3FB950;
    private static final int RED = 0xFFF85149;

    private TextView ipText;
    private TextView statusText;
    private TextView logText;
    private Button toggleBtn;
    private EditText socksPort;
    private EditText httpPort;
    private EditText fouPort;

    private EditText hsSsid;
    private EditText hsPass;
    private Button hsBtn;
    private Button p2pBtn;
    private TextView hsText;
    private CheckBox hsAuto;
    private CheckBox p2pAuto;
    private CheckBox bootCheck;
    private ImageView hsQr;
    private String hsQrKey = "";

    private EditText vpnGw;
    private Button vpnBtn;
    private TextView vpnText;
    private String pendingGw = "";

    private static final int REQ_PERM = 1;
    private static final int REQ_PERM_HS = 2;
    private static final int REQ_PERM_P2P = 3;
    private static final int REQ_VPN = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean bound;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshIp();
            refreshState();
            refreshClient();
            refreshHotspot();
            ui.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        askNotificationPermission();
        handleCmd(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCmd(intent);
    }

    /**
     * 支持命令行/自动化触发：
     * adb shell am start -n com.dosgo.gateway/.MainActivity --es cmd hotspot_on
     * cmd 取值：hotspot_on / hotspot_off / start / stop
     */
    private void handleCmd(Intent it) {
        if (it == null) {
            return;
        }
        String cmd = it.getStringExtra("cmd");
        if (cmd == null) {
            return;
        }
        if ("hotspot_on".equals(cmd)) {
            if (!HotspotController.active && !HotspotController.wantActive) {
                toggleHotspot();
            }
        } else if ("hotspot_off".equals(cmd)) {
            HotspotController.stop();
            refreshHotspot();
        } else if ("start".equals(cmd)) {
            if (!GatewayService.running) {
                toggleService();
            }
        } else if ("stop".equals(cmd)) {
            if (GatewayService.running) {
                toggleService();
            }
        } else if ("p2p_on".equals(cmd)) {
            // 同一颗 Wi-Fi 芯片不能并存两种 AP：热点开着就拒绝
            if (HotspotController.active || HotspotController.isStarting()) {
                Logs.add("p2p_on 拒绝：热点开着，先关热点");
                Toast.makeText(this, "热点开着时用不了直连：先关掉热点", Toast.LENGTH_SHORT).show();
            } else {
                String err = P2pController.start(this, P2pController.FIXED_SSID, P2pController.FIXED_PASS);
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                }
            }
            refreshHotspot();
        } else if ("p2p_off".equals(cmd)) {
            P2pController.stop();
        } else if ("vpn_on".equals(cmd)) {
            String gw = it.getStringExtra("gw");
            if (gw != null && vpnGw != null) {
                vpnGw.setText(gw);
            }
            toggleClientVpn();
        } else if ("vpn_off".equals(cmd)) {
            Intent i = new Intent(this, ClientVpnService.class);
            i.setAction(ClientVpnService.ACTION_STOP);
            startService(i);
        } else if ("porttest".equals(cmd)) {
            Logs.add("端口探测 " + P2pController.probeDhcpPort());
        } else if ("prefs".equals(cmd)) {
            Logs.add("prefs: 开机自启=" + AppPrefs.bootStart(this)
                    + " 自动热点=" + HotspotController.prefAuto(this)
                    + " 自动直连组=" + P2pController.prefAuto(this));
        } else if ("auto_hs_on".equals(cmd)) {
            hsAuto.setChecked(true);
            Logs.add("prefs: 自动热点已开＝" + HotspotController.prefAuto(this));
        } else if ("auto_hs_off".equals(cmd)) {
            hsAuto.setChecked(false);
            Logs.add("prefs: 自动热点已关＝" + HotspotController.prefAuto(this));
        } else if ("auto_p2p_on".equals(cmd)) {
            p2pAuto.setChecked(true);
            Logs.add("prefs: 自动直连组已开＝" + P2pController.prefAuto(this));
        } else if ("auto_p2p_off".equals(cmd)) {
            p2pAuto.setChecked(false);
            Logs.add("prefs: 自动直连组已关＝" + P2pController.prefAuto(this));
        } else if ("boot_on".equals(cmd)) {
            AppPrefs.setBootStart(this, true);
            bootCheck.setChecked(true);
            Logs.add("prefs: 开机自启已开");
        } else if ("boot_off".equals(cmd)) {
            AppPrefs.setBootStart(this, false);
            bootCheck.setChecked(false);
            Logs.add("prefs: 开机自启已关");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------ UI 构建

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("网关");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("SOCKS5 · HTTP · FOU 局域网代理网关");
        sub.setTextColor(MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        root.addView(sub, subLp);

        // ---- 本机 IP 卡片 ----
        LinearLayout ipCard = card();
        root.addView(ipCard, cardLp());

        ipCard.addView(label("本机 IP 地址"));
        ipText = new TextView(this);
        ipText.setTextColor(ACCENT);
        ipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        ipText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ipText.setPadding(0, dp(8), 0, 0);
        ipCard.addView(ipText);

        TextView ipHint = new TextView(this);
        ipHint.setText("同一 Wi-Fi 下的电脑 / 手机把代理指向这个地址，点按可复制");
        ipHint.setTextColor(MUTED);
        ipHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ipHint.setPadding(0, dp(8), 0, 0);
        ipCard.addView(ipHint);

        ipCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = NetUtils.primaryIp();
                if (ip == null) {
                    Toast.makeText(MainActivity.this, "未获取到 IP", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("ip", ip));
                    Toast.makeText(MainActivity.this, "已复制 " + ip, Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ---- 端口 ----
        LinearLayout portCard = card();
        root.addView(portCard, cardLp());
        portCard.addView(label("服务端口"));

        socksPort = portRow(portCard, "SOCKS5", String.valueOf(AppPrefs.socksPort(this)));
        httpPort = portRow(portCard, "HTTP", String.valueOf(AppPrefs.httpPort(this)));
        fouPort = portRow(portCard, "FOU (UDP)", String.valueOf(AppPrefs.fouPort(this)));

        // ---- 自启动 ----
        LinearLayout bootCard = card();
        root.addView(bootCard, cardLp());
        bootCard.addView(label("自启动"));

        bootCheck = new CheckBox(this);
        bootCheck.setText("开机自动启动网关");
        bootCheck.setTextColor(TEXT);
        bootCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bootCheck.setChecked(AppPrefs.bootStart(this));
        bootCheck.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                AppPrefs.setBootStart(MainActivity.this, checked);
            }
        });
        LinearLayout.LayoutParams bootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bootLp.topMargin = dp(8);
        bootCard.addView(bootCheck, bootLp);

        TextView bootHint = new TextView(this);
        bootHint.setText("勾上后开机会自动拉起网关服务；热点卡片里再勾「启动网关时自动开热点」，"
                + "开机后热点也会一起开。\n"
                + "国产系统（HyperOS / EMUI / ColorOS）还要在系统「自启动管理」里放行本应用，否则收不到开机广播。");
        bootHint.setTextColor(MUTED);
        bootHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bootHint.setPadding(0, dp(6), 0, 0);
        bootHint.setLineSpacing(dp(2), 1f);
        bootCard.addView(bootHint);

        Button bootBtn = new Button(this);
        bootBtn.setText("去系统里允许自启动");
        bootBtn.setTextColor(TEXT);
        bootBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bootBtn.setAllCaps(false);
        bootBtn.setBackground(roundRect(0xFF30363D, 10, 0, 0));
        LinearLayout.LayoutParams bootBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        bootBtnLp.topMargin = dp(10);
        bootCard.addView(bootBtn, bootBtnLp);
        bootBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutostartSettings();
            }
        });

        // ---- 客户端模式（另一台设备用） ----
        LinearLayout vpnCard = card();
        root.addView(vpnCard, cardLp());
        vpnCard.addView(label("客户端模式（另一台设备装本 App）"));

        TextView vpnHint = new TextView(this);
        vpnHint.setText("不再依赖系统代理：整机流量（含 DNS、UDP）直接转给网关。"
                + "Wi-Fi 怎么断怎么重连都不影响，专治「重连后代理失效 / DNS 报错」");
        vpnHint.setTextColor(MUTED);
        vpnHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        vpnHint.setPadding(0, dp(6), 0, 0);
        vpnHint.setLineSpacing(dp(2), 1f);
        vpnCard.addView(vpnHint);

        vpnGw = textRow(vpnCard, "网关地址",
                getSharedPreferences("gateway_client", Context.MODE_PRIVATE)
                        .getString("gw", ""), InputType.TYPE_CLASS_TEXT);

        vpnBtn = new Button(this);
        vpnBtn.setText("启动客户端隧道");
        vpnBtn.setTextColor(0xFFFFFFFF);
        vpnBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        vpnBtn.setAllCaps(false);
        vpnBtn.setBackground(roundRect(0xFF1F6FEB, 10, 0, 0));
        LinearLayout.LayoutParams vpnBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        vpnBtnLp.topMargin = dp(10);
        vpnCard.addView(vpnBtn, vpnBtnLp);
        vpnBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleClientVpn();
            }
        });

        vpnText = new TextView(this);
        vpnText.setTextColor(MUTED);
        vpnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        vpnText.setPadding(0, dp(10), 0, 0);
        vpnText.setLineSpacing(dp(3), 1f);
        vpnText.setText("○ 未启用（填网关设备的 IP，端口用上面的 FOU 端口，默认 5555）");
        vpnCard.addView(vpnText);

        // ---- 热点 ----
        LinearLayout hsCard = card();
        root.addView(hsCard, cardLp());
        hsCard.addView(label("自带热点（Local-Only，不需要系统热点）"));

        hsSsid = textRow(hsCard, "SSID", HotspotController.prefSsid(this), InputType.TYPE_CLASS_TEXT);
        hsPass = textRow(hsCard, "密码", HotspotController.prefPass(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        // 两个「自动开」是互斥的：同一个 Wi-Fi 芯片只能当一种 AP，
        // 勾一个就把另一个取消，避免拉起网关时两种一起开、互相把对方挤掉。
        hsAuto = new CheckBox(this);
        hsAuto.setText("启动网关时自动开热点（Local-Only）");
        hsAuto.setTextColor(MUTED);
        hsAuto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hsAuto.setChecked(HotspotController.prefAuto(this));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.topMargin = dp(10);
        hsCard.addView(hsAuto, autoLp);

        p2pAuto = new CheckBox(this);
        p2pAuto.setText("启动网关时自动开固定名称热点（Wi-Fi Direct）");
        p2pAuto.setTextColor(MUTED);
        p2pAuto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        p2pAuto.setChecked(P2pController.prefAuto(this));
        LinearLayout.LayoutParams autoLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp2.topMargin = dp(4);
        hsCard.addView(p2pAuto, autoLp2);

        hsAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (checked && p2pAuto.isChecked()) {
                    p2pAuto.setChecked(false);
                }
                HotspotController.save(MainActivity.this, hsSsid.getText().toString().trim(),
                        hsPass.getText().toString(), checked);
            }
        });
        p2pAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (checked && hsAuto.isChecked()) {
                    hsAuto.setChecked(false);
                }
                P2pController.setPrefAuto(MainActivity.this, checked);
            }
        });

        hsBtn = new Button(this);
        hsBtn.setText("开启热点");
        hsBtn.setTextColor(0xFFFFFFFF);
        hsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hsBtn.setAllCaps(false);
        hsBtn.setBackground(roundRect(0xFF238636, 10, 0, 0));
        LinearLayout.LayoutParams hsBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        hsBtnLp.topMargin = dp(12);
        hsCard.addView(hsBtn, hsBtnLp);
        hsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHotspot();
            }
        });

        p2pBtn = new Button(this);
        p2pBtn.setText("固定名称热点（Wi-Fi Direct）");
        p2pBtn.setTextColor(0xFFFFFFFF);
        p2pBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        p2pBtn.setAllCaps(false);
        p2pBtn.setBackground(roundRect(0xFF238636, 10, 0, 0));
        LinearLayout.LayoutParams p2pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        p2pLp.topMargin = dp(8);
        hsCard.addView(p2pBtn, p2pLp);
        p2pBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleP2p();
            }
        });

        hsText = new TextView(this);
        hsText.setTextColor(MUTED);
        hsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hsText.setPadding(0, dp(10), 0, 0);
        hsText.setLineSpacing(dp(3), 1f);
        hsCard.addView(hsText);

        hsText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ssid;
                String pass;
                String ip;
                if (P2pController.active) {
                    ssid = P2pController.ssid;
                    pass = P2pController.pass;
                    ip = NetUtils.primaryIp();
                } else if (HotspotController.active) {
                    ssid = HotspotController.ssid;
                    pass = HotspotController.pass;
                    ip = HotspotController.apIp == null || HotspotController.apIp.isEmpty()
                            ? HotspotController.detectApIp() : HotspotController.apIp;
                } else {
                    return;
                }
                String info = "SSID: " + ssid
                        + "\nPASS: " + pass
                        + "\nSOCKS5 " + ip + ":" + GatewayService.lastSocksPort
                        + "\nHTTP   " + ip + ":" + GatewayService.lastHttpPort
                        + "\nFOU    " + ip + ":" + GatewayService.lastFouPort + "/udp"
                        + "\nPAC    http://" + ip + ":" + GatewayService.lastHttpPort + "/proxy.pac";
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("hotspot", info));
                    Toast.makeText(MainActivity.this, "连接信息已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });

        hsQr = new ImageView(this);
        hsQr.setVisibility(View.GONE);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(200), dp(200));
        qrLp.topMargin = dp(12);
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        hsCard.addView(hsQr, qrLp);

        // ---- 启动按钮 ----
        toggleBtn = new Button(this);
        toggleBtn.setText("启动网关");
        toggleBtn.setTextColor(0xFFFFFFFF);
        toggleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        toggleBtn.setAllCaps(false);
        toggleBtn.setBackground(roundRect(ACCENT, 12, 0, 0));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(18);
        root.addView(toggleBtn, btnLp);
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });

        // ---- 状态 ----
        LinearLayout stCard = card();
        LinearLayout.LayoutParams stLp = cardLp();
        root.addView(stCard, stLp);
        stCard.addView(label("运行状态"));
        statusText = new TextView(this);
        statusText.setTextColor(MUTED);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusText.setPadding(0, dp(8), 0, 0);
        statusText.setLineSpacing(dp(3), 1f);
        stCard.addView(statusText);

        // ---- 日志 ----
        LinearLayout logCard = card();
        root.addView(logCard, cardLp());
        logCard.addView(label("日志"));
        logText = new TextView(this);
        logText.setTextColor(MUTED);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(0, dp(8), 0, 0);
        logText.setLineSpacing(dp(2), 1f);
        logCard.addView(logText);

        TextView tip = new TextView(this);
        tip.setText("用法\n"
                + "· 电脑系统代理 / SwitchyOmega：SOCKS5 或 HTTP → 手机上显示的 IP + 对应端口\n"
                + "· 浏览器可打开 http://IP:HTTP端口 查看网关状态页\n"
                + "· 没有路由器也能用：本页开启热点，电脑连该 Wi-Fi，代理地址填热点里的「网关地址」\n"
                + "· 懒人模式：客户端「自动配置脚本」填 http://IP:HTTP端口/proxy.pac，一次搞定 HTTP+SOCKS5\n"
                + "· 想固定 SSID：用「Wi-Fi Direct 固定组」，名字就是 DIRECT-gwGateway（先打开 Wi-Fi 开关）\n"
                + "· FOU：对端 Linux 执行 ip fou add port 5555 ipproto 4，再把流量路由到本机");
        tip.setTextColor(0xFF6E7681);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(22);
        root.addView(tip, tipLp);

        setContentView(scroll);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private EditText portRow(LinearLayout parent, String name, String def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(row, lp);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText et = new EditText(this);
        et.setText(def);
        et.setTextColor(ACCENT);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTypeface(Typeface.MONOSPACE);
        et.setGravity(Gravity.CENTER);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setBackground(roundRect(0xFF0D1117, 8, 1, BORDER));
        et.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(et, etLp);
        return et;
    }

    private EditText textRow(LinearLayout parent, String name, String def, int inputType) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(row, lp);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText et = new EditText(this);
        et.setText(def);
        et.setTextColor(ACCENT);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTypeface(Typeface.MONOSPACE);
        et.setGravity(Gravity.CENTER_VERTICAL);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setBackground(roundRect(0xFF0D1117, 8, 1, BORDER));
        et.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(170),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(et, etLp);
        return et;
    }

    private LinearLayout card() {        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(roundRect(CARD, 14, 1, BORDER));
        l.setPadding(dp(16), dp(14), dp(16), dp(16));
        return l;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        return lp;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ------------------------------------------------------------ 交互

    private void toggleService() {
        if (GatewayService.running) {
            Intent i = new Intent(this, GatewayService.class);
            i.setAction(GatewayService.ACTION_STOP);
            startService(i);
            if (HotspotController.active) {
                HotspotController.stop();
            }
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshState();
                    refreshHotspot();
                }
            }, 300);
            return;
        }
        int sp = parsePort(socksPort.getText().toString(), 1080);
        int hp = parsePort(httpPort.getText().toString(), 8080);
        int fp = parsePort(fouPort.getText().toString(), 5555);
        if (sp == hp || sp == fp || hp == fp) {
            Toast.makeText(this, "三个端口不能重复", Toast.LENGTH_SHORT).show();
            return;
        }
        saveHotspotPrefs();
        AppPrefs.savePorts(this, sp, hp, fp);
        if (p2pAuto.isChecked() && !P2pController.active && !P2pController.isStarting()) {
            if (hasHotspotPermissions()) {
                startP2pGroup();
            } else {
                requestPermissions(hotspotPermissions(), REQ_PERM_P2P);
            }
        } else if (hsAuto.isChecked() && !HotspotController.active) {
            if (hasHotspotPermissions()) {
                HotspotController.start(this);
            } else {
                requestPermissions(hotspotPermissions(), REQ_PERM_HS);
            }
        }
        Intent i = new Intent(this, GatewayService.class);
        i.setAction(GatewayService.ACTION_START);
        i.putExtra(GatewayService.EXTRA_SOCKS, sp);
        i.putExtra(GatewayService.EXTRA_HTTP, hp);
        i.putExtra(GatewayService.EXTRA_FOU, fp);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshState();
            }
        }, 600);
    }

    // ------------------------------------------------------------ 热点

    private String[] hotspotPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.NEARBY_WIFI_DEVICES",
                    "android.permission.ACCESS_FINE_LOCATION"};
        }
        return new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    private boolean hasHotspotPermissions() {
        for (String p : hotspotPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------- 客户端模式

    private void toggleClientVpn() {
        if (ClientVpnService.running) {
            Intent i = new Intent(this, ClientVpnService.class);
            i.setAction(ClientVpnService.ACTION_STOP);
            startService(i);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshClient();
                }
            }, 500);
            return;
        }
        String gw = vpnGw.getText() == null ? "" : vpnGw.getText().toString().trim();
        if (gw.isEmpty()) {
            Toast.makeText(this, "先填网关设备的 IP（就是那台手机首页显示的本机 IP）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("gateway_client", Context.MODE_PRIVATE)
                .edit().putString("gw", gw).apply();
        pendingGw = gw;
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            try {
                startActivityForResult(prep, REQ_VPN);
            } catch (Throwable t) {
                Toast.makeText(this, "打不开 VPN 授权页：" + t, Toast.LENGTH_LONG).show();
            }
        } else {
            startClientVpn(gw);
        }
    }

    private void startClientVpn(String gw) {
        String ps = fouPort.getText() == null ? "" : fouPort.getText().toString();
        int p = parsePort(ps, 5555);
        Intent i = new Intent(this, ClientVpnService.class);
        i.setAction(ClientVpnService.ACTION_START);
        i.putExtra(ClientVpnService.EXTRA_GW, gw);
        i.putExtra(ClientVpnService.EXTRA_PORT, p);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：" + t, Toast.LENGTH_LONG).show();
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshClient();
            }
        }, 900);
    }

    private void refreshClient() {
        if (vpnText == null) {
            return;
        }
        if (ClientVpnService.running) {
            vpnBtn.setText("停止客户端隧道");
            vpnBtn.setBackground(roundRect(0xFF30363D, 10, 0, 0));
            vpnText.setTextColor(GREEN);
            vpnText.setText("● 隧道运行中 → " + ClientVpnService.gwIp + ":" + ClientVpnService.gwPort
                    + "\n上行 " + ClientVpnService.upPkts + " 包 / 下行 " + ClientVpnService.downPkts + " 包"
                    + "\n这台设备的全部流量都走网关，不用再配系统代理");
            return;
        }
        vpnBtn.setText("启动客户端隧道");
        vpnBtn.setBackground(roundRect(0xFF1F6FEB, 10, 0, 0));
        String err = ClientVpnService.lastError;
        if (err != null && !err.isEmpty()) {
            vpnText.setTextColor(RED);
            vpnText.setText("○ " + err);
        } else {
            vpnText.setTextColor(MUTED);
            vpnText.setText("○ 未启用（填网关设备的 IP，端口用上面的 FOU 端口，默认 5555）");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startClientVpn(pendingGw);
            } else {
                Toast.makeText(this, "没有授权 VPN，客户端模式用不了", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toggleHotspot() {
        saveHotspotPrefs();
        if (HotspotController.active || HotspotController.isStarting()) {
            HotspotController.stop();
            refreshHotspot();
            return;
        }
        if (!hasHotspotPermissions()) {
            requestPermissions(hotspotPermissions(), REQ_PERM_HS);
            return;
        }
        String err = HotspotController.start(this);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
        }
        refreshHotspot();
    }

    private void scheduleHotspotRefresh() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshHotspot();
            }
        }, 1500);
    }

    private void toggleP2p() {
        if (P2pController.active || P2pController.isStarting()) {
            P2pController.stop();
            refreshHotspot();
            return;
        }
        if (HotspotController.active || HotspotController.isStarting()) {
            Toast.makeText(this, "热点开着时用不了直连：先关掉热点", Toast.LENGTH_SHORT).show();
            refreshHotspot();
            return;
        }
        if (!hasHotspotPermissions()) {
            requestPermissions(hotspotPermissions(), REQ_PERM_P2P);
            return;
        }
        startP2pGroup();
        refreshHotspot();
        scheduleHotspotRefresh();
    }

    /** 真正去建 P2P 组（Wi-Fi 没开就直接失败）。 */
    private String startP2pGroup() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && !wm.isWifiEnabled()) {
            String msg = "P2P 需要 Wi-Fi 打开：请先打开 Wi-Fi 开关（不用连网络）";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return msg;
        }
        String err = P2pController.start(this, P2pController.FIXED_SSID, P2pController.FIXED_PASS);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
        }
        return err;
    }

    private void saveHotspotPrefs() {
        String s = hsSsid.getText().toString().trim();
        String p = hsPass.getText().toString();
        if (s.isEmpty()) {
            s = HotspotController.DEFAULT_SSID;
            hsSsid.setText(s);
        }
        if (p.length() < 8) {
            p = HotspotController.DEFAULT_PASS;
            hsPass.setText(p);
        }
        HotspotController.save(this, s, p, hsAuto.isChecked());
        P2pController.setPrefAuto(this, p2pAuto.isChecked());
    }

    /** 把「SSID + 密码」画成 Wi-Fi 二维码，另一台手机扫一下就连上（SSID 随机时特别有用）。 */
    private void showQr(String ssid, String pass) {
        if (hsQr == null || ssid == null || ssid.isEmpty()) {
            return;
        }
        final String key = ssid + "|" + pass;
        if (key.equals(hsQrKey)) {
            hsQr.setVisibility(View.VISIBLE);
            return;
        }
        try {
            String content = "WIFI:T:WPA;S:" + ssid + ";P:" + pass + ";;";
            BitMatrix bm = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 480, 480);
            int w = bm.getWidth();
            int h = bm.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = bm.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            hsQr.setImageBitmap(bmp);
            hsQr.setVisibility(View.VISIBLE);
            hsQrKey = key;
        } catch (Throwable t) {
            hsQr.setVisibility(View.GONE);
            hsQrKey = "";
        }
    }

    private void refreshHotspot() {
        if (hsText == null) {
            return;
        }
        // 建组时组信息可能还没准备好，靠网卡状态把 active 补正，避免明明开着却显示「未开启」
        P2pController.syncActive();
        if (P2pController.active) {
            p2pBtn.setText("解散 Wi-Fi Direct 组");
            p2pBtn.setBackground(roundRect(0xFF30363D, 10, 0, 0));
            p2pBtn.setTextColor(0xFFFFFFFF);
            p2pBtn.setEnabled(true);
            // 同一个 Wi-Fi 芯片只能当一种 AP：直连组开着时热点不可用
            hsBtn.setEnabled(false);
            hsBtn.setTextColor(MUTED);
            hsBtn.setText("开启热点");
            hsBtn.setBackground(roundRect(0xFF21262D, 10, 0, 0));
            hsSsid.setEnabled(false);
            hsPass.setEnabled(false);
            String ip = NetUtils.primaryIp();
            StringBuilder sb = new StringBuilder();
            sb.append("● Wi-Fi Direct 组已开启（SSID 固定）\nSSID：").append(P2pController.ssid)
                    .append("    密码：").append(P2pController.pass)
                    .append("\n网关地址：").append(ip == null ? "?" : ip)
                    .append("\n客户端把它当普通 WPA2 Wi-Fi 连（不是扫 Wi-Fi Direct 设备）；")
                    .append("P2P 组没有互联网共享，所以必须走代理")
                    .append("\n⚠ 这组没有上游 DNS：客户端本地解析必然失败，浏览器请用 PAC 或 HTTP 代理");
            hsText.setTextColor(GREEN);
            hsText.setText(sb.toString());
            hsQrKey = "";
            showQr(P2pController.ssid, P2pController.pass);
            return;
        }
        boolean hotOn = HotspotController.active;
        if (hotOn || HotspotController.isStarting()) {
            // 热点开着时，直连按钮置灰不可点：同一颗 Wi-Fi 芯片不能同时当两种 AP
            p2pBtn.setText("热点开启中，直连不可用");
            p2pBtn.setTextColor(0xFF6E7681);
            p2pBtn.setBackground(roundRect(0xFF21262D, 10, 0, 0));
            p2pBtn.setEnabled(false);
        } else {
            p2pBtn.setText(P2pController.isStarting() ? "正在建组…" : "固定名称热点（Wi-Fi Direct）");
            p2pBtn.setTextColor(0xFFFFFFFF);
            p2pBtn.setBackground(roundRect(0xFF238636, 10, 0, 0));
            p2pBtn.setEnabled(true);
        }
        hsBtn.setEnabled(true);
        hsBtn.setTextColor(0xFFFFFFFF);
        if (hotOn) {
            hsBtn.setText("关闭热点");
            hsBtn.setBackground(roundRect(0xFF30363D, 10, 0, 0));
            hsSsid.setEnabled(false);
            hsPass.setEnabled(false);
            String ip = HotspotController.apIp;
            if (ip == null || ip.isEmpty()) {
                ip = HotspotController.detectApIp();
                HotspotController.apIp = ip;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("● 热点已开启\nSSID：").append(HotspotController.ssid)
                    .append("    密码：").append(HotspotController.pass)
                    .append("\n网关地址：").append(ip.isEmpty() ? "启动后稍候刷新" : ip)
                    .append("\n客户端连上这个 Wi-Fi 后，把代理指向上面的网关地址 + 服务端口")
                    .append("\n⚠ 这组没有上游 DNS：客户端本地解析必然失败，浏览器请用 PAC 或 HTTP 代理");
            if (HotspotController.note != null && !HotspotController.note.isEmpty()) {
                sb.append("\n⚠ ").append(HotspotController.note);
            }
            hsText.setTextColor(GREEN);
            hsText.setText(sb.toString());
            showQr(HotspotController.ssid, HotspotController.pass);
        } else {
            hsBtn.setText(HotspotController.isStarting() ? "正在开启…" : "开启热点");
            hsBtn.setBackground(roundRect(0xFF238636, 10, 0, 0));
            hsSsid.setEnabled(true);
            hsPass.setEnabled(true);
            String n = HotspotController.note;
            hsText.setTextColor(n != null && !n.isEmpty() ? RED : MUTED);
            hsText.setText(n != null && !n.isEmpty() ? "○ " + n
                    : "○ 未开启（Local-Only 热点：只建 Wi-Fi，不做系统级共享，客户端走代理上网）");
            hsQr.setVisibility(View.GONE);
            hsQrKey = "";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_P2P) {
            if (hasHotspotPermissions()) {
                toggleP2p();
            } else {
                Toast.makeText(this, "没有权限就开不了 P2P 组", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQ_PERM_HS) {
            if (hasHotspotPermissions()) {
                String err = HotspotController.start(this);
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "没有权限就开不了热点", Toast.LENGTH_SHORT).show();
            }
            refreshHotspot();
        }
    }

    private int parsePort(String s, int def) {        try {
            int v = Integer.parseInt(s.trim());
            return (v > 0 && v < 65536) ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 跳到各家 ROM 的「自启动管理」页；跳不过去就退回应用详情页。 */
    private void openAutostartSettings() {
        Intent mi = new Intent("miui.intent.action.OP_AUTO_START");
        mi.addCategory(Intent.CATEGORY_DEFAULT);
        if (startIfResolvable(mi)) {
            return;
        }
        Intent editor = new Intent("miui.intent.action.APP_PERM_EDITOR");
        editor.putExtra("extra_pkgname", getPackageName());
        if (startIfResolvable(editor)) {
            return;
        }
        if (startIfResolvable(new Intent("com.huawei.systemmanager.startupmgr.action.SHOW_APPS"))) {
            return;
        }
        if (startIfResolvable(new Intent("com.coloros.safecenter.startupapp.StartupAppListActivity"))) {
            return;
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "跳不过去，请手动到系统设置里找「自启动管理」", Toast.LENGTH_LONG).show();
        }
    }

    private boolean startIfResolvable(Intent i) {
        try {
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private void refreshIp() {
        List<NetUtils.Nic> list = NetUtils.interfaces();
        if (list.isEmpty()) {
            ipText.setText("未连接网络");
            return;
        }
        // 网卡名只做「这是什么网络」的提示，用短标签 + 小字号挤在同一行，避免长名字把 IP 挤到换行
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(list.get(i).ip);
            int from = sb.length();
            sb.append("  ").append(nicTag(list.get(i).name));
            int to = sb.length();
            sb.setSpan(new RelativeSizeSpan(0.55f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(MUTED), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ipText.setText(sb);
    }

    /** 网卡名压缩成短标签：p2p-p2p0-1 → P2P，ccmni2 → 蜂窝。 */
    private static String nicTag(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("p2p")) {
            return "P2P";
        }
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")) {
            return "热点";
        }
        if (n.startsWith("wlan")) {
            return "Wi-Fi";
        }
        if (n.startsWith("ccmni") || n.startsWith("rmnet") || n.startsWith("clat")) {
            return "蜂窝";
        }
        if (n.startsWith("eth")) {
            return "有线";
        }
        if (n.startsWith("tun") || n.startsWith("ppp")) {
            return "VPN";
        }
        return name;
    }

    private void refreshState() {
        boolean run = GatewayService.running;
        boolean changed = run != bound;
        bound = run;
        if (run) {
            toggleBtn.setText("停止网关");
            toggleBtn.setBackground(roundRect(0xFF30363D, 12, 0, 0));
            statusText.setTextColor(GREEN);
            String st = "● 运行中\nSOCKS5 :" + GatewayService.lastSocksPort
                    + "    HTTP :" + GatewayService.lastHttpPort
                    + "    FOU :" + GatewayService.lastFouPort + "/udp";
            if (FouEngine.stats.length() > 0) {
                st += "\nFOU " + FouEngine.stats;
            }
            statusText.setText(st);
            socksPort.setEnabled(false);
            httpPort.setEnabled(false);
            fouPort.setEnabled(false);
        } else {
            toggleBtn.setText("启动网关");
            toggleBtn.setBackground(roundRect(ACCENT, 12, 0, 0));
            statusText.setTextColor(RED);
            statusText.setText("○ 已停止");
            socksPort.setEnabled(true);
            httpPort.setEnabled(true);
            fouPort.setEnabled(true);
            if (!changed) {
                ipText.setTextColor(ACCENT);
            }
        }
        String log = Logs.dump();
        if (!log.equals(logText.getText().toString())) {
            logText.setText(log.isEmpty() ? "—" : log);
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            } catch (Exception ignore) {
            }
        }
    }
}
