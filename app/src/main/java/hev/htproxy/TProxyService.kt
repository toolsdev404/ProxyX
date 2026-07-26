package hev.htproxy

/**
 * JNI bridge to the bundled hev-socks5-tunnel engine (libhev-socks5-tunnel.so).
 *
 * IMPORTANT: The native library's JNI_OnLoad looks up EXACTLY this class by its
 * default name — package "hev/htproxy", class "TProxyService" — and binds the four
 * native methods below to it. If the package name, the class name, or ANY of the
 * four method signatures don't match the engine's src/hev-jni.c, the library
 * aborts (SIGABRT) the instant it loads. Do not rename or remove anything here.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    @Suppress("unused")
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    @Suppress("unused")
    external fun TProxyGetStats(): LongArray
}