package com.example.reelblocker

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Local DNS-filtering VPN. Only DNS traffic (to the fake DNS server 10.0.0.1)
 * is routed into the tunnel; everything else goes out normally.
 * Blocked domains get a 0.0.0.0 answer, others are forwarded to a real DNS server.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.reelblocker.START"
        const val ACTION_STOP = "com.example.reelblocker.STOP"
        @Volatile var running = false

        private const val TAG = "ReelBlocker"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val DNS_ADDRESS = "10.0.0.1"
        private const val UPSTREAM_DNS = "8.8.8.8"

        // Only these apps are routed through the VPN (others are untouched).
        private val TARGET_APPS = listOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.instagram.lite"
        )

        // Strict mode: blocks ALL media from these domains (images too).
        private val STRICT_DOMAINS = listOf("fbcdn.net", "cdninstagram.com", "fbsbx.com")
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (!running) startVpn()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("Reel Blocker")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)

        var added = 0
        for (pkg in TARGET_APPS) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: PackageManager.NameNotFoundException) {
                // app not installed
            }
        }
        if (added == 0) Log.w(TAG, "No target app installed; routing DNS of all apps")

        tun = builder.establish() ?: return
        running = true
        worker = Thread { runLoop() }.also { it.start() }
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        worker?.interrupt()
        worker = null
        stopSelf()
    }

    private fun runLoop() {
        val fd = tun?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                handlePacket(buf.copyOf(n), output)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Tunnel closed: ${e.message}")
        } finally {
            running = false
        }
    }

    private fun handlePacket(pkt: ByteArray, output: FileOutputStream) {
        if (pkt.size < 28) return
        if ((pkt[0].toInt() shr 4) != 4) return            // IPv4 only
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt[9].toInt() != 17) return                   // UDP only
        if (pkt.size < ihl + 8) return

        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        if (dstPort != 53) return

        val udpLen = u16(pkt, ihl + 4)
        val end = minOf(pkt.size, ihl + udpLen)
        if (end <= ihl + 8) return
        val dns = pkt.copyOfRange(ihl + 8, end)
        val q = parseQuestion(dns) ?: return

        if (isDomainBlocked(q.name)) {
            Log.d(TAG, "BLOCK ${q.name}")
            val reply = blockedResponse(dns, q)
            write(output, wrap(dstIp, srcIp, 53, srcPort, reply))
        } else {
            Log.d(TAG, "ALLOW ${q.name}")
            pool.execute {
                try {
                    DatagramSocket().use { s ->
                        protect(s)                          // bypass the VPN
                        s.soTimeout = 4000
                        s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(UPSTREAM_DNS), 53))
                        val rb = ByteArray(4096)
                        val dp = DatagramPacket(rb, rb.size)
                        s.receive(dp)
                        write(output, wrap(dstIp, srcIp, 53, srcPort, rb.copyOf(dp.length)))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Forward failed for ${q.name}: ${e.message}")
                }
            }
        }
    }

    // ---- Filtering -------------------------------------------------------

    private fun isDomainBlocked(host: String): Boolean {
        val strict = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("strict", false)
        if (strict) {
            return STRICT_DOMAINS.any { host == it || host.endsWith(".$it") }
        }
        // Default mode: only hosts that look like video servers.
        val onMetaCdn = host.endsWith(".fbcdn.net") || host.endsWith(".cdninstagram.com")
        return onMetaCdn && host.contains("video")
    }

    // ---- DNS helpers -----------------------------------------------------

    private class Question(val name: String, val type: Int, val end: Int)

    private fun parseQuestion(d: ByteArray): Question? {
        if (d.size < 17) return null
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= d.size) return null
            val len = d[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0) return null
            if (i + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        if (i + 4 > d.size) return null
        return Question(sb.toString().lowercase(), u16(d, i), i + 4)
    }

    /** A records -> 0.0.0.0, everything else -> empty NOERROR. */
    private fun blockedResponse(d: ByteArray, q: Question): ByteArray {
        val qLen = q.end - 12
        val isA = q.type == 1
        val out = ByteArray(12 + qLen + if (isA) 16 else 0)
        out[0] = d[0]; out[1] = d[1]                       // transaction id
        out[2] = 0x81.toByte(); out[3] = 0x80.toByte()     // response, RD, RA
        out[5] = 1                                         // QDCOUNT = 1
        if (isA) out[7] = 1                                // ANCOUNT = 1
        System.arraycopy(d, 12, out, 12, qLen)
        if (isA) {
            val a = intArrayOf(
                0xC0, 0x0C,             // name pointer
                0x00, 0x01,             // type A
                0x00, 0x01,             // class IN
                0x00, 0x00, 0x00, 0x3C, // TTL 60
                0x00, 0x04,             // RDLENGTH
                0, 0, 0, 0              // 0.0.0.0
            )
            for (k in a.indices) out[12 + qLen + k] = a[k].toByte()
        }
        return out
    }

    // ---- IP / UDP helpers ------------------------------------------------

    private fun wrap(src: ByteArray, dst: ByteArray, sPort: Int, dPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64                                          // TTL
        p[9] = 17                                          // UDP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val ck = checksum(p, 20)
        p[10] = (ck shr 8).toByte(); p[11] = ck.toByte()
        p[20] = (sPort shr 8).toByte(); p[21] = sPort.toByte()
        p[22] = (dPort shr 8).toByte(); p[23] = dPort.toByte()
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)  // UDP checksum 0 = none (valid for IPv4)
        return p
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun write(out: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) {
            try { out.write(packet) } catch (_: IOException) {}
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
