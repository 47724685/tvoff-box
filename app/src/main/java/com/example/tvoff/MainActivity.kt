package com.example.tvoff

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.concurrent.Executors

/**
 * 装在当贝盒子上: 点一下图标 -> 盒子冒充蓝牙遥控器 -> 向已配对的长虹电视发送 Power 键 -> 自动退出。
 * 第一次使用会显示电视列表, 选一次之后以后点开即自动执行。
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private var hid: BluetoothHidDevice? = null
    private var adapter: BluetoothAdapter? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var btnReselect: Button
    private lateinit var btnUsage: Button
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var devices: List<BluetoothDevice> = emptyList()
    private var auto = false
    private var sent = false

    // Consumer Control(Report ID 2): Power=0x0030, Sleep=0x0032
    private val usage get() = prefs.getInt("usage", 0x0030)

    // 与 Bluetooth Remote(Atharok) 相同的组合描述符: Keyboard(ID 1) + Consumer(ID 2) + Mouse(ID 3)
    // 很多电视只认"像键盘/鼠标"的设备, 只有 Consumer 一个集合时可能被忽略
    private val descriptor = byteArrayOf(
        // Consumer Control, Report ID 2
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
        0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03, 0x75, 0x10, 0x95.toByte(), 0x01,
        0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x81.toByte(), 0x00, 0xC0.toByte(),
        // Keyboard, Report ID 1
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x75, 0x08, 0x95.toByte(), 0x01, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00,
        0x05, 0x07, 0x19, 0x00, 0x29, 0xFF.toByte(), 0x81.toByte(), 0x00, 0xC0.toByte(),
        // Mouse, Report ID 3
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03,
        0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x03, 0x81.toByte(), 0x02,
        0x75, 0x05, 0x95.toByte(), 0x01, 0x81.toByte(), 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte()
    )
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) ui.post { start() }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            ui.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        status.text = "已连接: ${device.name}"
                        if (auto && !sent) { sent = true; sendPowerThenQuit(device) }
                    }
                    BluetoothProfile.STATE_CONNECTING -> status.text = "连接中…"
                    else -> if (!sent) status.text = "未连接"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tvoff", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 60, 80, 60)
        }
        status = TextView(this).apply { textSize = 24f; text = "启动中…" }
        btnReselect = Button(this).apply {
            text = "重新选择电视"
            setOnClickListener { prefs.edit().remove("tv").apply(); auto = false; showList() }
        }
        btnUsage = Button(this).apply {
            setOnClickListener {
                prefs.edit().putInt("usage", if (usage == 0x0030) 0x0032 else 0x0030).apply()
                updateUsageLabel()
            }
        }
        val btnDiscover = Button(this).apply {
            text = "让电视能搜到本盒子(首次配对用)"
            setOnClickListener {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                })
            }
        }
        list = ListView(this)
        root.addView(status); root.addView(btnReselect); root.addView(btnUsage)
        root.addView(btnDiscover); root.addView(list)
        setContentView(root)
        updateUsageLabel()

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else init()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) init()
        else status.text = "需要蓝牙权限"
    }

    private fun updateUsageLabel() {
        btnUsage.text = "按键类型: " + (if (usage == 0x0030) "Power(默认)" else "Sleep") + "  (点此切换)"
    }

    private fun init() {
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val a = adapter
        if (a == null || !a.isEnabled) { status.text = "请先打开蓝牙"; return }

        a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "TV Remote", "Remote", "App",
                    BluetoothHidDevice.SUBCLASS1_COMBO, descriptor
                )
                if (!h.registerApp(sdp, null, null, executor, callback)) {
                    ui.post { status.text = "本盒子不支持蓝牙HID设备模式" }
                }
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun start() {
        val addr = prefs.getString("tv", null)
        val dev = addr?.let { s -> adapter?.bondedDevices?.firstOrNull { it.address == s } }
        if (dev != null) {
            auto = true
            status.text = "正在连接 ${dev.name}…"
            hid?.connect(dev)
            ui.postDelayed({ if (!sent) status.text = "连接超时: 请确认电视已开机且蓝牙已配对" }, 10000)
        } else {
            showList()
        }
    }

    private fun showList() {
        status.text = "请选择电视(需先在盒子蓝牙设置里与电视配对)"
        devices = adapter?.bondedDevices?.toList() ?: emptyList()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            devices.map { (it.name ?: "未知") + "  " + it.address })
        list.setOnItemClickListener { _, _, i, _ ->
            prefs.edit().putString("tv", devices[i].address).apply()
            auto = true; sent = false
            status.text = "正在连接 ${devices[i].name}…"
            hid?.connect(devices[i])
        }
        list.requestFocus()
    }

    private fun sendPowerThenQuit(d: BluetoothDevice) {
        val h = hid ?: return
        val u = usage
        h.sendReport(d, 2, byteArrayOf((u and 0xFF).toByte(), (u shr 8).toByte()))
        ui.postDelayed({ h.sendReport(d, 2, byteArrayOf(0, 0)) }, 120)
        ui.postDelayed({ status.text = "已发送关机指令"; finish() }, 700)
    }

    override fun onDestroy() {
        try { hid?.unregisterApp() } catch (_: Exception) {}
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        super.onDestroy()
    }
}
