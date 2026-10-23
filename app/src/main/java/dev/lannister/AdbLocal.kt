package dev.lannister

import android.content.SharedPreferences
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * A small adb client that talks to the TV's own adb daemon (ADB debugging must be on) at 127.0.0.1:5555.
 *
 * Why: Android holds back activity starts from background apps for 5 seconds after a Home press, so Lannister opening
 * itself over the Amazon home screen takes 5 seconds. A command run through adb runs as the "shell" user, which is
 * exempt, so `am start` through here opens Lannister at once. (The Launcher Manager app does the same.)
 *
 * The first connection makes the TV ask "Allow USB debugging?" for Lannister's key; choose "Always allow".
 * Each call opens its own connection, so nothing stays open between Home presses.
 */
internal class AdbLocal(private val prefs: SharedPreferences, private val port: Int = 5555, private val log: (String) -> Unit = {}) {

    /** Runs [command] as the shell user and returns what it printed, or null if adb was not reachable or refused us. */
    fun shell(command: String, authMs: Int = 3000): String? = try {
        exec(command, authMs)
    } catch (e: Exception) {
        log("adb: $e")
        null
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun exec(command: String, authMs: Int): String {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 3000
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = BufferedOutputStream(s.getOutputStream())
            send(out, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var step = 0
            var m = read(input)
            while (m.cmd == A_AUTH) {
                if (m.arg0 != AUTH_TOKEN) throw IOException("unexpected auth message")
                when (step++) {
                    0 -> send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(m.data)) // a key the TV already trusts
                    1 -> { // first time: offer the key; the TV asks the person
                        s.soTimeout = authMs
                        send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob())
                    }
                    else -> throw IOException("the TV did not accept the key")
                }
                m = read(input)
            }
            if (m.cmd != A_CNXN) throw IOException("no connection reply")
            s.soTimeout = 4000
            send(out, A_OPEN, 1, 0, "shell:$command\u0000".toByteArray())
            val text = StringBuilder()
            while (true) {
                val r = try {
                    read(input)
                } catch (e: SocketTimeoutException) {
                    break
                }
                when (r.cmd) {
                    A_WRTE -> {
                        text.append(String(r.data))
                        send(out, A_OKAY, 1, r.arg0, ByteArray(0))
                    }
                    A_CLSE -> return text.toString()
                }
            }
            return text.toString()
        }
    }

    private fun send(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        out.write(h.array())
        out.write(data)
        out.flush()
    }

    private fun read(input: DataInputStream): Msg {
        val raw = ByteArray(24)
        input.readFully(raw)
        val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val arg0 = h.int
        val arg1 = h.int
        val len = h.int
        h.int // data checksum: the connection is local, so it is not checked
        val magic = h.int
        if (magic != cmd.inv() || len < 0 || len > 1 shl 20) throw IOException("bad adb message")
        val data = ByteArray(len)
        if (len > 0) input.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    // ---- key ----

    private fun keyPair(): KeyPair {
        val priv = prefs.getString("adb_priv", null)
        val pub = prefs.getString("adb_pub", null)
        if (priv != null && pub != null) try {
            val kf = KeyFactory.getInstance("RSA")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))),
            )
        } catch (e: Exception) { // damaged: make a new one
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        prefs.edit()
            .putString("adb_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("adb_pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    /** adb signs the 20-byte token as if it were a SHA-1 hash: PKCS#1 v1.5 over the SHA-1 DigestInfo and the token. */
    private fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(keyPair().private)
        sig.update(SHA1_DIGEST_INFO + token)
        return sig.sign()
    }

    /** adb's own public key layout (len, n0inv, modulus, R^2 mod n, exponent; 32-bit little-endian words), in base64. */
    private fun publicKeyBlob(): ByteArray {
        val pub = keyPair().public as RSAPublicKey
        val n = pub.modulus
        val words = 64
        val two32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(32 * words)
        val rr = r.multiply(r).mod(n)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).mod(two32)
        val b = ByteBuffer.allocate(4 + 4 + 4 * words * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        b.putInt(n0inv.toInt())
        for (i in 0 until words) b.putInt(n.shiftRight(32 * i).and(two32.subtract(BigInteger.ONE)).toInt())
        for (i in 0 until words) b.putInt(rr.shiftRight(32 * i).and(two32.subtract(BigInteger.ONE)).toInt())
        b.putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " lannister@firetv\u0000").toByteArray()
    }

    private companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
