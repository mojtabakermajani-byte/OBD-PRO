package ir.obdpro

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var connected = false
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var values: LinearLayout
    private val adapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val readings = linkedMapOf("RPM" to "—", "سرعت" to "—", "دمای آب" to "—", "فشار MAP" to "—", "دریچه گاز" to "—")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (adapter == null) { showFatal("این گوشی بلوتوث ندارد."); return }
        buildUi()
        requestBtPermissions()
        loadPaired()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 28)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFF101114.toInt())
        }
        scroll.addView(page)
        setContentView(scroll)

        page.addView(TextView(this).apply {
            text = "OBD PRO فارسی"
            textSize = 27f; setTextColor(0xFFFF8A00.toInt()); gravity = Gravity.CENTER
        })
        page.addView(TextView(this).apply {
            text = "نسخه آزمایشی ۰.۱ | ارتباط Bluetooth Classic"
            textSize = 13f; setTextColor(0xFFBBBBBB.toInt()); gravity = Gravity.CENTER
        })
        status = label("وضعیت: متصل نیست", 16f, 0xFFFFB74D.toInt())
        page.addView(status)
        deviceSpinner = Spinner(this)
        page.addView(deviceSpinner)
        connectButton = button("اتصال به دانگل")
        page.addView(connectButton)
        connectButton.setOnClickListener { if (connected) disconnect() else connectSelected() }

        page.addView(label("اطلاعات زنده موتور", 20f, 0xFFFF8A00.toInt()))
        values = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(values)
        refreshReadings()

        page.addView(button("خواندن خطاهای موتور (Mode 03)").apply {
            setOnClickListener { runCommand("03", "کدهای خطای ذخیره‌شده") }
        })
        page.addView(button("خواندن خطاهای Pending (Mode 07)").apply {
            setOnClickListener { runCommand("07", "خطاهای در انتظار") }
        })
        page.addView(button("پاک کردن خطاهای موتور (Mode 04)").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("هشدار ایمنی")
                    .setMessage("فقط پس از رفع علت خرابی و با اطلاع از اینکه پاک‌کردن خطا ممکن است داده‌های عیب‌یابی و آمادگی آلایندگی را پاک کند، ادامه دهید.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("پاک کردن") { _, _ -> runCommand("04", "نتیجه پاک کردن خطا") }
                    .show()
            }
        })
        page.addView(button("به‌روزرسانی اطلاعات زنده").apply {
            setOnClickListener { pollLive() }
        })
        output = label("خروجی فرمان‌ها در این قسمت نمایش داده می‌شود.", 14f, 0xFFE0E0E0.toInt())
        output.setPadding(0, 18, 0, 18)
        page.addView(output)
        page.addView(label("توجه: این نسخه فقط OBD-II استاندارد موتور را آزمایش می‌کند. دسترسی ABS، SRS/ایربگ و TCU با ELM327 معمولی تضمین نمی‌شود.", 13f, 0xFFFFCC80.toInt()))
    }

    private fun label(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setPadding(0, 12, 0, 12)
    }
    private fun button(t: String) = Button(this).apply {
        text = t; isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFFDD7600.toInt())
    }
    private fun showFatal(t: String) {
        setContentView(TextView(this).apply { text = t; textSize = 20f; setPadding(30,30,30,30) })
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 10)
        }
    }

    private fun loadPaired() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                status.text = "برای فهرست دانگل‌ها مجوز بلوتوث را تأیید کنید."
                return
            }
            val devices = adapter.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
            if (devices.isEmpty()) {
                deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("هیچ دستگاه Pair‌شده‌ای نیست"))
            } else {
                deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                    devices.map { "${it.name ?: "Bluetooth"} (${it.address})" })
                deviceSpinner.tag = devices
            }
        } catch (e: SecurityException) { status.text = "مجوز بلوتوث داده نشده است." }
    }

    @Suppress("DEPRECATION")
    private fun connectSelected() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBtPermissions(); return
        }
        val devices = deviceSpinner.tag as? List<BluetoothDevice>
        val device = devices?.getOrNull(deviceSpinner.selectedItemPosition)
        if (device == null) { status.text = "ابتدا دانگل را از تنظیمات گوشی Pair کنید."; return }
        status.text = "در حال اتصال به ${device.name} ..."
        thread {
            try {
                adapter.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(spp)
                s.connect()
                socket = s
                val init = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
                init.forEach { command(it) }
                connected = true
                runOnUiThread {
                    status.text = "متصل به ${device.name}"
                    connectButton.text = "قطع اتصال"
                    pollLive()
                }
            } catch (e: Exception) {
                closeSocket()
                runOnUiThread { status.text = "اتصال ناموفق: ${e.message ?: "دانگل پاسخ نداد"}" }
            }
        }
    }

    private fun disconnect() {
        closeSocket()
        connected = false
        status.text = "وضعیت: متصل نیست"
        connectButton.text = "اتصال به دانگل"
    }
    private fun closeSocket() { try { socket?.close() } catch (_: Exception) {}; socket = null; connected = false }

    @Synchronized
    @Throws(IOException::class)
    private fun command(cmd: String): String {
        val s = socket ?: throw IOException("اتصال برقرار نیست")
        val out = s.outputStream
        val input = s.inputStream
        while (input.available() > 0) input.read()
        out.write((cmd.trim() + "\r").toByteArray(Charsets.US_ASCII)); out.flush()
        val result = StringBuilder()
        val deadline = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == 62) break // >
                if (b >= 0) result.append(b.toChar())
            } else Thread.sleep(20)
        }
        return result.toString().replace("\r", " ").replace("\n", " ").trim()
    }

    private fun runCommand(cmd: String, title: String) {
        if (!connected) { output.text = "ابتدا به ELM327 متصل شوید."; return }
        thread {
            try {
                val response = command(cmd)
                runOnUiThread { output.text = "$title\nفرمان: $cmd\nپاسخ خام: $response" }
            } catch (e: Exception) { runOnUiThread { output.text = "خطا: ${e.message}" } }
        }
    }

    private fun pollLive() {
        if (!connected) return
        thread {
            val results = linkedMapOf<String, String>()
            try {
                val rpm = command("010C")
                results["RPM"] = parsePid(rpm, "410C") { b -> (((b[0] * 256) + b[1]) / 4).toString() }
                val speed = command("010D")
                results["سرعت"] = parsePid(speed, "410D") { b -> "${b[0]} km/h" }
                val temp = command("0105")
                results["دمای آب"] = parsePid(temp, "4105") { b -> "${b[0] - 40} °C" }
                val map = command("010B")
                results["فشار MAP"] = parsePid(map, "410B") { b -> "${b[0]} kPa" }
                val throttle = command("0111")
                results["دریچه گاز"] = parsePid(throttle, "4111") { b -> "%.1f%%".format((b[0] * 100.0) / 255.0) }
                runOnUiThread {
                    readings.putAll(results)
                    refreshReadings()
                    output.text = "اطلاعات از ECU دریافت شد.\n" + results.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                }
            } catch (e: Exception) { runOnUiThread { output.text = "خطا در دریافت اطلاعات: ${e.message}" } }
        }
    }

    private fun parsePid(raw: String, marker: String, convert: (List<Int>) -> String): String {
        val hex = raw.uppercase().replace(Regex("[^0-9A-F]"), "")
        val idx = hex.indexOf(marker)
        if (idx < 0 || idx + marker.length + 4 > hex.length) return "پشتیبانی‌نشده / پاسخ نامعتبر"
        val bytes = (0..1).map { hex.substring(idx + marker.length + it * 2, idx + marker.length + (it + 1) * 2).toInt(16) }
        return try { convert(bytes) } catch (_: Exception) { "پاسخ نامعتبر" }
    }

    private fun refreshReadings() {
        if (!::values.isInitialized) return
        values.removeAllViews()
        readings.forEach { (name, value) ->
            values.addView(TextView(this).apply {
                text = "$name: $value"; textSize = 17f; setTextColor(0xFFFFFFFF.toInt())
                setPadding(12, 9, 12, 9); setBackgroundColor(0xFF24262B.toInt())
            })
        }
    }

    override fun onDestroy() { closeSocket(); super.onDestroy() }
}
