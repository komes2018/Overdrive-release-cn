package com.overdrive.app.byd

import android.os.IBinder
import android.os.Parcel
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.shell.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Direct bridge to BYD's `autoservice` AIDL surface.
 *
 * Wire format empirically verified on DiLink 3.0 (Android 10, build 13.1.33.2506260.1):
 *  - Service registered as `autoservice` with descriptor `android.gui.BYDAutoServer`.
 *  - Shell uid (the daemon's uid) can dispatch transactions; they reach the
 *    service's parser. Calls without the right interface token return
 *    `0xffffffb6` ("Not a data message"), confirming the gate is the token,
 *    not the caller uid.
 *
 * Two surfaces exposed today:
 *  1. Read paths: `getInt(area, cmd)` and `getBuffer(area, cmd)` — query HAL state.
 *  2. Remote-control envelope (tx=40): `writeInt(5); writeInt(opcode)` where
 *     opcode is one of 5/6/7/9/17/22 (UNLOCK/TRUNK/LOCK/POWER_OFF/CLOSE_WINDOWS/FLASH).
 *
 * Lifecycle: stays connected for the daemon lifetime. `init()` lazily resolves
 * the binder via `ServiceManager.getService("autoservice")`. If `Binder.transact`
 * raises `SecurityException`, falls back to `service call autoservice <tx> …`
 * which detours through the `service` binary (also shell uid).
 */
object AutoServiceBridge {
    private const val TAG = "AutoServiceBridge"
    private const val SERVICE_NAME = "autoservice"
    private const val INTERFACE_TOKEN = "android.gui.BYDAutoServer"

    // Transaction IDs
    private const val TX_GET_INT = 6
    private const val TX_GET_BUFFER = 8
    private const val TX_REMOTE_CONTROL = 40
    private const val TX_SEND_CAN = 42

    // Remote-control envelope opcodes (writeInt(5); writeInt(opcode))
    const val OP_UNLOCK = 5
    const val OP_TRUNK_OPEN = 6
    const val OP_LOCK = 7
    const val OP_POWER_OFF = 9
    const val OP_CLOSE_WINDOWS = 17
    const val OP_FLASH_LIGHTS = 22

    // Sentinel returns
    const val INVALID_VALUE = -10011    // 0xffffd8e5, BYD HAL "no such area/cmd"
    const val ERROR_NO_BINDER = -1
    const val ERROR_TRANSACT_FALSE = -2
    const val ERROR_TRANSACT_EXC = -3
    const val ERROR_SHELL_CALL = -10010

    @Volatile private var binder: IBinder? = null
    @Volatile private var useShellCall: Boolean = false
    @Volatile private var initialized: Boolean = false
    @Volatile private var diLink5InterfaceToken: String? = null

    private val parcelInt32Pattern: Pattern = Pattern.compile("0x([0-9a-fA-F]{8})")
    private val parcelByteHexPattern: Pattern = Pattern.compile("0x([0-9a-fA-F]{2})")

    data class DiLink5Read(
        @JvmField val status: Int,
        @JvmField val valueBits: Int
    )

    /** Resolves the binder and detects whether shell-fallback is needed. Idempotent. */
    @Synchronized
    fun init(): Boolean {
        if (initialized) return binder != null || useShellCall
        if (!HiddenApiBypass.isBypassed()) HiddenApiBypass.bypass()

        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val b = getService.invoke(null, SERVICE_NAME) as? IBinder
            if (b != null && b.pingBinder()) {
                binder = b
                detectCallMode()
                log("connected via ServiceManager (useShellCall=$useShellCall)")
            } else {
                log("ServiceManager.getService($SERVICE_NAME) returned null — shell fallback only")
                useShellCall = true
            }
        } catch (e: Exception) {
            log("init failed: ${e.javaClass.simpleName}: ${e.message} — shell fallback only")
            useShellCall = true
        }
        initialized = true
        return binder != null || useShellCall
    }

    fun isConnected(): Boolean {
        if (!initialized) init()
        val b = binder ?: return useShellCall
        return try { b.pingBinder() } catch (_: Exception) { false }
    }

    fun isUsingShellCall(): Boolean = useShellCall

    // ────────────────────────────────────────────────────────────────────
    // Read paths
    // ────────────────────────────────────────────────────────────────────

    /** `getInt(area, cmd)` — returns the int at the given HAL coordinate, or `INVALID_VALUE`. */
    fun getInt(area: Int, cmd: Int): Int {
        if (!initialized) init()
        return if (useShellCall) {
            shellGetInt(TX_GET_INT, "i32 $area", "i32 $cmd")
        } else {
            transactInt(TX_GET_INT) {
                it.writeInterfaceToken(INTERFACE_TOKEN)
                it.writeInt(area)
                it.writeInt(cmd)
            }
        }
    }

    /** `getBuffer(area, cmd)` — returns the byte buffer at the coordinate, or `null`. */
    fun getBuffer(area: Int, cmd: Int): ByteArray? {
        if (!initialized) init()
        return if (useShellCall) {
            shellGetBytes(TX_GET_BUFFER, "i32 $area", "i32 $cmd")
        } else {
            transactBuffer {
                it.writeInterfaceToken(INTERFACE_TOKEN)
                it.writeInt(area)
                it.writeInt(cmd)
            }
        }
    }

    /**
     * DI5 read-side wire format: tx 5 returns an int and tx 7 returns IEEE-754
     * float bits. The service writes status first and value second.
     */
    fun readDiLink5(tx: Int, device: Int, featureId: Int): DiLink5Read? {
        if (!DiLink5Platform.isSelected() || (tx != 5 && tx != 7)) return null
        val b = liveDiLink5Binder() ?: return null
        val token = diLink5InterfaceToken ?: try {
            b.interfaceDescriptor?.also { diLink5InterfaceToken = it }
        } catch (_: Throwable) {
            null
        }
        if (token.isNullOrEmpty()) return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(token)
            data.writeInt(device)
            data.writeInt(featureId)
            if (!b.transact(tx, data, reply, 0)) return null
            reply.setDataPosition(0)
            if (reply.dataAvail() < 8) return null
            DiLink5Read(reply.readInt(), reply.readInt())
        } catch (t: Throwable) {
            log("readDiLink5(tx=$tx,dev=$device,fid=$featureId): "
                    + "${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Remote control (lock/unlock/trunk/etc.)
    // ────────────────────────────────────────────────────────────────────

    fun unlockCar(): Int = remoteControl(OP_UNLOCK)
    fun lockCar(): Int = remoteControl(OP_LOCK)
    fun openTrunk(): Int = remoteControl(OP_TRUNK_OPEN)
    fun powerOff(): Int = remoteControl(OP_POWER_OFF)
    fun closeWindows(): Int = remoteControl(OP_CLOSE_WINDOWS)
    fun flashLights(): Int = remoteControl(OP_FLASH_LIGHTS)

    /**
     * Returns the service's int reply, or one of the `ERROR_*` sentinels.
     *
     * <p>Unlike the read-side, the write-side ({@link #TX_REMOTE_CONTROL},
     * {@link #TX_SEND_CAN}) DOES wrap the reply in a Parcel exception envelope.
     * control.apk's smali confirms: {@code readException(); readInt()}.
     */
    fun remoteControl(opcode: Int): Int {
        if (!initialized) init()
        return if (useShellCall) {
            shellGetInt(TX_REMOTE_CONTROL, "i32 5", "i32 $opcode")
        } else {
            transactIntWithException(TX_REMOTE_CONTROL) {
                it.writeInterfaceToken(INTERFACE_TOKEN)
                it.writeInt(5)         // remote-control envelope marker
                it.writeInt(opcode)
            }
        }
    }

    /**
     * Sends a CAN frame via the autoservice route. Hex format: `"0xID DD DD DD DD DD DD DD DD"`
     * (matches the format BYD's daemon parses). Returns 0 on success, negative on error.
     *
     * NOTE: high-risk surface — the daemon may forward this to the gateway. Gate caller-side.
     */
    fun sendCanViaService(hexFrame: String): Int {
        if (!initialized) init()
        return if (useShellCall) {
            shellGetInt(TX_SEND_CAN, "i32 0", "s16 \"$hexFrame\"")
        } else {
            transactIntWithException(TX_SEND_CAN) {
                it.writeInterfaceToken(INTERFACE_TOKEN)
                it.writeInt(0)
                it.writeString(hexFrame)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Internals
    // ────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun liveDiLink5Binder(): IBinder? {
        binder?.let {
            try {
                if (it.pingBinder()) return it
            } catch (_: Throwable) {
            }
        }
        binder = null
        diLink5InterfaceToken = null
        if (!HiddenApiBypass.isBypassed()) HiddenApiBypass.bypass()
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val resolved = sm.getMethod("getService", String::class.java)
                .invoke(null, SERVICE_NAME) as? IBinder
            if (resolved != null && resolved.pingBinder()) {
                binder = resolved
                resolved
            } else {
                null
            }
        } catch (t: Throwable) {
            log("DI5 binder resolve failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun detectCallMode() {
        val b = binder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // Probe with a known-safe read (tx=8, area=0, cmd=0). Token-stamped, so
            // INVALID_VALUE in reply means dispatch worked end-to-end.
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(0)
            data.writeInt(0)
            b.transact(TX_GET_BUFFER, data, reply, 0)
            useShellCall = false
        } catch (_: SecurityException) {
            useShellCall = true
            log("Binder.transact blocked by SELinux — falling back to shell")
        } catch (_: Exception) {
            // Unknown failure — keep direct mode and let calls surface real errors.
            useShellCall = false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Read-side transactions ({@link #TX_GET_INT}, {@link #TX_GET_BUFFER}) write
     * their int response directly — no Parcel exception envelope. Calling
     * {@code readException()} here misinterprets BYD's INVALID_VALUE sentinel
     * (-10011) as an "Unknown exception code" and throws. The control.apk
     * smali confirms the convention: {@code setDataPosition(0); readInt()}
     * with NO {@code readException()} for the read side.
     */
    private inline fun transactInt(tx: Int, write: (Parcel) -> Unit): Int {
        val b = binder
        if (b == null) {
            log("transactInt(tx=$tx): no binder")
            return ERROR_NO_BINDER
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            write(data)
            val ok = b.transact(tx, data, reply, 0)
            if (!ok) {
                log("transactInt(tx=$tx): transact returned false")
                ERROR_TRANSACT_FALSE
            } else {
                reply.setDataPosition(0)
                reply.readInt()
            }
        } catch (e: Exception) {
            log("transactInt(tx=$tx): ${e.javaClass.simpleName}: ${e.message}")
            ERROR_TRANSACT_EXC
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Write-side transactions ({@link #TX_REMOTE_CONTROL}, {@link #TX_SEND_CAN})
     * do wrap the reply in a Parcel exception envelope — control.apk's smali
     * confirms {@code readException(); readInt()} for both.
     */
    private inline fun transactIntWithException(tx: Int, write: (Parcel) -> Unit): Int {
        val b = binder
        if (b == null) {
            log("transactIntWithException(tx=$tx): no binder")
            return ERROR_NO_BINDER
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            write(data)
            val ok = b.transact(tx, data, reply, 0)
            if (!ok) {
                log("transactIntWithException(tx=$tx): transact returned false")
                ERROR_TRANSACT_FALSE
            } else {
                try {
                    reply.readException()
                } catch (e: Exception) {
                    log("transactIntWithException(tx=$tx): readException threw ${e.javaClass.simpleName}: ${e.message}")
                    return ERROR_TRANSACT_EXC
                }
                val v = reply.readInt()
                log("transactIntWithException(tx=$tx) -> $v (0x${java.lang.Integer.toHexString(v)})")
                v
            }
        } catch (e: Exception) {
            log("transactIntWithException(tx=$tx): ${e.javaClass.simpleName}: ${e.message}")
            ERROR_TRANSACT_EXC
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private inline fun transactBuffer(write: (Parcel) -> Unit): ByteArray? {
        val b = binder
        if (b == null) {
            log("transactBuffer: no binder")
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            write(data)
            val ok = b.transact(TX_GET_BUFFER, data, reply, 0)
            if (!ok) {
                log("transactBuffer: transact returned false")
                null
            } else {
                reply.setDataPosition(0)
                val len = reply.readInt()
                val replyDataSize = reply.dataSize()
                log("transactBuffer: replyDataSize=$replyDataSize len=$len (0x${java.lang.Integer.toHexString(len)})")
                if (len in 1..4096) ByteArray(len).also { reply.readByteArray(it) } else null
            }
        } catch (e: Exception) {
            log("transactBuffer: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun shellGetInt(tx: Int, vararg args: String): Int {
        val out = shellServiceCall(tx, *args) ?: return ERROR_SHELL_CALL
        val m = parcelInt32Pattern.matcher(out)
        return if (m.find()) {
            try { java.lang.Long.parseLong(m.group(1)!!, 16).toInt() }
            catch (_: Exception) { ERROR_SHELL_CALL }
        } else ERROR_SHELL_CALL
    }

    private fun shellGetBytes(tx: Int, vararg args: String): ByteArray? {
        val out = shellServiceCall(tx, *args) ?: return null
        val m = parcelByteHexPattern.matcher(out)
        val bytes = ArrayList<Byte>(64)
        while (m.find()) {
            try { bytes.add(java.lang.Integer.parseInt(m.group(1)!!, 16).toByte()) }
            catch (_: Exception) { return null }
        }
        return if (bytes.isEmpty()) null else bytes.toByteArray()
    }

    private fun shellServiceCall(tx: Int, vararg args: String): String? {
        val cmd = StringBuilder("service call ").append(SERVICE_NAME).append(' ').append(tx)
        for (a in args) cmd.append(' ').append(a)
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd.toString()))
            val text = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            p.waitFor()
            text.trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun log(msg: String) {
        try {
            // Match daemon-side logging idiom — CameraDaemon.log is the canonical sink.
            val cls = Class.forName("com.overdrive.app.daemon.CameraDaemon")
            val log = cls.getMethod("log", String::class.java)
            log.invoke(null, "$TAG: $msg")
        } catch (_: Exception) {
            android.util.Log.d(TAG, msg)
        }
    }
}
