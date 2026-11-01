package dev.nullphase.ghostty

/**
 * PTY transport (native side: src/main/cpp/pty_jni.cpp). Owns a fork/exec'd
 * shell attached to a pty slave as its controlling terminal. Methods may be
 * called from any thread; reads are blocking and belong to the session's
 * reader thread.
 */
class Pty private constructor(
    val pid: Int,
    val fd: Int,
) {
    companion object {
        init {
            System.loadLibrary("ghostty_android")
        }

        // Signals
        const val SIGHUP_NUM = 1
        const val SIGKILL_NUM = 9
        const val SIGTERM_NUM = 15

        /** Fork/exec [shell] on a new pty. Returns null on failure. */
        fun open(
            cols: Int,
            rows: Int,
            shell: String,
            args: List<String>,
            cwd: String,
            env: Map<String, String>,
        ): Pty? {
            val envp = env.map { (k, v) -> "$k=$v" }.toTypedArray()
            val argArray = args.toTypedArray()
            val ids = nativeOpen(cols, rows, shell, argArray, cwd, envp) ?: return null
            if (ids.size != 2 || ids[0] <= 0 || ids[1] < 0) return null
            return Pty(pid = ids[0], fd = ids[1])
        }

        fun exitCodeOf(status: Int): Int =
            if (status and 0x7f == 0) (status shr 8) and 0xff else -(status and 0x7f)

        // Native
        @JvmStatic private external fun nativeOpen(
            cols: Int,
            rows: Int,
            shell: String,
            args: Array<String>,
            cwd: String,
            envp: Array<String>,
        ): IntArray?

        @JvmStatic private external fun nativeRead(fd: Int, data: ByteArray): Int

        @JvmStatic private external fun nativeWrite(fd: Int, data: ByteArray): Int

        @JvmStatic private external fun nativeResize(fd: Int, cols: Int, rows: Int)

        @JvmStatic private external fun nativeSignal(pid: Int, sig: Int)

        @JvmStatic private external fun nativeWaitPid(pid: Int): Int

        @JvmStatic private external fun nativeCloseFd(fd: Int)
    }

    /** Blocking read; returns 0 on EOF, -1 on error. */
    fun read(data: ByteArray): Int = nativeRead(fd, data)

    /** Writes all bytes; returns total written or -1 on error. */
    fun write(data: ByteArray): Int = nativeWrite(fd, data)

    fun resize(cols: Int, rows: Int) = nativeResize(fd, cols, rows)

    /** Signal the shell process directly. */
    fun signal(sig: Int) = nativeSignal(pid, sig)

    /** Blocking waitpid; returns raw status (decode with [exitCodeOf]). */
    fun wait(): Int = nativeWaitPid(pid)

    fun closeFd() = nativeCloseFd(fd)
}
