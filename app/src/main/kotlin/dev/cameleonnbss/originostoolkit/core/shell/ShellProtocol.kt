package dev.cameleonnbss.originostoolkit.core.shell

import android.os.Parcel

/**
 * The wire format between the app and its Shizuku user service.
 *
 * This is a hand-written Binder protocol rather than an AIDL interface, on
 * purpose: AIDL is compiled by a native `aidl.exe` shipped in the build tools,
 * which fails on Windows accounts or project paths containing non-ASCII
 * characters ("C:\Users\José\..."). A handful of `Parcel` reads and writes cost
 * a few lines here and make the project build anywhere.
 *
 * The shape is exactly what AIDL would have generated: an interface descriptor,
 * a transaction code, a token check, and an exception slot.
 */
object ShellProtocol {

    const val DESCRIPTOR = "dev.cameleonnbss.originostoolkit.IUserService"

    const val TRANSACTION_EXEC = android.os.IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_WHOAMI = android.os.IBinder.FIRST_CALL_TRANSACTION + 1

    const val EXIT_PREFIX = "exit="
    const val EXIT_FAILURE = 127

    /**
     * Binder transactions are capped around 1 MB, and `dumpsys` output is not
     * bounded by anything. Truncating in the service keeps a large command from
     * turning into a TransactionTooLargeException.
     */
    const val MAX_OUTPUT_CHARS = 200_000

    const val TRUNCATED_MARKER = "\n[output truncated by OriginOS Toolkit]"

    /** `exit=<code>\n<merged output>` — one payload, so the code cannot get lost. */
    fun encode(code: Int, output: String): String {
        val safe = if (output.length > MAX_OUTPUT_CHARS) {
            output.take(MAX_OUTPUT_CHARS) + TRUNCATED_MARKER
        } else {
            output
        }
        return "$EXIT_PREFIX$code\n$safe"
    }

    fun decode(payload: String): Pair<Int, String> {
        val newline = payload.indexOf('\n')
        if (newline < 0 || !payload.startsWith(EXIT_PREFIX)) {
            return EXIT_FAILURE to payload
        }
        val code = payload.substring(EXIT_PREFIX.length, newline).trim().toIntOrNull() ?: EXIT_FAILURE
        return code to payload.substring(newline + 1)
    }

    fun writeToken(parcel: Parcel) = parcel.writeInterfaceToken(DESCRIPTOR)

    /** Parcels are pooled by the platform; never leave one leaked. */
    inline fun <T> withParcels(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
