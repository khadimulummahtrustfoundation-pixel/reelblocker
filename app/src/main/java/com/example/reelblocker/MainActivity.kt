package com.example.reelblocker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        status = TextView(this).apply { textSize = 20f; setPadding(0, 0, 0, pad) }

        val strict = Switch(this).apply {
            text = "কঠোর মোড (ছবিও ব্লক হতে পারে)"
            isChecked = prefs.getBoolean("strict", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("strict", checked).apply()
            }
        }

        val hint = TextView(this).apply {
            text = "বন্ধ থাকলে শুধু ভিডিও-সংক্রান্ত হোস্ট ব্লক হয়। " +
                "কাজ না করলে কঠোর মোড চালু করুন।"
            setPadding(0, pad / 2, 0, pad)
        }

        button = Button(this).apply { setOnClickListener { toggle() } }

        layout.addView(status)
        layout.addView(strict)
        layout.addView(hint)
        layout.addView(button)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = DnsVpnService.running
        status.text = if (on) "ব্লকিং চালু ✅" else "ব্লকিং বন্ধ ⛔"
        button.text = if (on) "বন্ধ করুন" else "চালু করুন"
    }

    private fun toggle() {
        if (DnsVpnService.running) {
            startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            button.postDelayed({ refresh() }, 500)
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, 1) else startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
        button.postDelayed({ refresh() }, 500)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
    }
}
