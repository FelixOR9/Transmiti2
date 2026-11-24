package com.transmiti2.transmiti2.streaming

import android.content.Context
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Display
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ClassLoader
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * U53 (subetapa 0, grupo 0-e): `getRecordingsDir()` a nivel handler.
 *
 * El manejador se instancia de verdad en JVM (sin Robolectric): el
 * `Context` es un falso que solo implementa `filesDir` (los otros
 * 101 métodos abstractos de `android.content.Context` API 36
 * lanzan `UnsupportedOperationException`; se generaron
 * mecánicamente desde `javap android.content.Context`, no a mano),
 * el `PermissionManager` se construye con su constructor inyectable
 * (historial falso, sin `SharedPreferences`) y el
 * `MethodChannel.Result` es el stub de Flutter del harness
 * (`repro2/stub-java`). El `mainDispatcher` es un ejecutor falso
 * de un hilo ("TestMain"), así que la respuesta en Main se prueba
 * por identidad de hilo, nunca por nombre (con `-ea` las
 * corrutinas decoran el nombre del hilo).
 *
 * Qué prueba este archivo y qué NO: la ruta devuelve la ruta con
 * `textureRegistry`/`cameraManager` nulos, `previewEngine` no se
 * materializa (el `lazy` sigue sin inicializar, por reflexión) y
 * la respuesta llega en el dispatcher Main inyectado. Que el
 * trabajo de disco corra en IO lo prueba U54 a nivel de flujo
 * (`RecordingsDirResolverTest`, con `RecordingFs` que registra
 * hilos): el manejador no expone costura de FS, así que esa
 * aserción no se duplica aquí.
 */
class StreamChannelHandlerGetRecordingsDirTest {

    /**
     * `Context` falso: solo `filesDir` (y `applicationContext`)
     * funcionan. Se instancia gracias al `Context` mockable del
     * harness (`repro3/mockable-java/android/content/Context.java`:
     * el `android.jar` crudo lanza `Stub!` hasta en el
     * constructor). Los otros 101 métodos abstractos de
     * `android.content.Context` API 36 lanzan
     * `UnsupportedOperationException`; se generaron mecánicamente
     * desde `javap android.content.Context`, no a mano.
     */
    private class FakeContext(private val filesDir: File?) : Context() {
        override fun getFilesDir(): File? = filesDir

        override fun getApplicationContext(): Context = this


    override fun bindService(p0: Intent, p1: ServiceConnection, p2: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun checkCallingOrSelfPermission(p0: String): Int {
        throw UnsupportedOperationException()
    }

    override fun checkCallingOrSelfUriPermission(p0: Uri?, p1: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun checkCallingPermission(p0: String): Int {
        throw UnsupportedOperationException()
    }

    override fun checkCallingUriPermission(p0: Uri?, p1: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun checkPermission(p0: String, p1: Int, p2: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun checkSelfPermission(p0: String): Int {
        throw UnsupportedOperationException()
    }

    override fun checkUriPermission(p0: Uri?, p1: Int, p2: Int, p3: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun checkUriPermission(p0: Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun clearWallpaper(): Unit {
        throw UnsupportedOperationException()
    }

    override fun createConfigurationContext(p0: Configuration): Context? {
        throw UnsupportedOperationException()
    }

    override fun createContextForSplit(p0: String?): Context? {
        throw UnsupportedOperationException()
    }

    override fun createDeviceProtectedStorageContext(): Context? {
        throw UnsupportedOperationException()
    }

    override fun createDisplayContext(p0: Display): Context? {
        throw UnsupportedOperationException()
    }

    override fun createPackageContext(p0: String?, p1: Int): Context? {
        throw UnsupportedOperationException()
    }

    override fun databaseList(): Array<String?> {
        throw UnsupportedOperationException()
    }

    override fun deleteDatabase(p0: String?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun deleteFile(p0: String?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun deleteSharedPreferences(p0: String?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun enforceCallingOrSelfPermission(p0: String, p1: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun enforceCallingOrSelfUriPermission(p0: Uri?, p1: Int, p2: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun enforceCallingPermission(p0: String, p1: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun enforceCallingUriPermission(p0: Uri?, p1: Int, p2: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun enforcePermission(p0: String, p1: Int, p2: Int, p3: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun enforceUriPermission(p0: Uri?, p1: Int, p2: Int, p3: Int, p4: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun enforceUriPermission(p0: Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int, p6: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun fileList(): Array<String?> {
        throw UnsupportedOperationException()
    }

    override fun getApplicationInfo(): ApplicationInfo? {
        throw UnsupportedOperationException()
    }

    override fun getAssets(): AssetManager? {
        throw UnsupportedOperationException()
    }

    override fun getCacheDir(): File? {
        throw UnsupportedOperationException()
    }

    override fun getClassLoader(): ClassLoader? {
        throw UnsupportedOperationException()
    }

    override fun getCodeCacheDir(): File? {
        throw UnsupportedOperationException()
    }

    override fun getContentResolver(): ContentResolver? {
        throw UnsupportedOperationException()
    }

    override fun getDataDir(): File? {
        throw UnsupportedOperationException()
    }

    override fun getDatabasePath(p0: String?): File? {
        throw UnsupportedOperationException()
    }

    override fun getDir(p0: String?, p1: Int): File? {
        throw UnsupportedOperationException()
    }

    override fun getExternalCacheDir(): File? {
        throw UnsupportedOperationException()
    }

    override fun getExternalCacheDirs(): Array<File?> {
        throw UnsupportedOperationException()
    }

    override fun getExternalFilesDir(p0: String?): File? {
        throw UnsupportedOperationException()
    }

    override fun getExternalFilesDirs(p0: String?): Array<File?> {
        throw UnsupportedOperationException()
    }

    override fun getExternalMediaDirs(): Array<File?> {
        throw UnsupportedOperationException()
    }

    override fun getFileStreamPath(p0: String?): File? {
        throw UnsupportedOperationException()
    }

    override fun getMainLooper(): Looper? {
        throw UnsupportedOperationException()
    }

    override fun getNoBackupFilesDir(): File? {
        throw UnsupportedOperationException()
    }

    override fun getObbDir(): File? {
        throw UnsupportedOperationException()
    }

    override fun getObbDirs(): Array<File?> {
        throw UnsupportedOperationException()
    }

    override fun getPackageCodePath(): String? {
        throw UnsupportedOperationException()
    }

    override fun getPackageManager(): PackageManager? {
        throw UnsupportedOperationException()
    }

    override fun getPackageName(): String? {
        throw UnsupportedOperationException()
    }

    override fun getPackageResourcePath(): String? {
        throw UnsupportedOperationException()
    }

    override fun getResources(): Resources? {
        throw UnsupportedOperationException()
    }

    override fun getSharedPreferences(p0: String?, p1: Int): SharedPreferences? {
        throw UnsupportedOperationException()
    }

    override fun getSystemService(p0: String): Any? {
        throw UnsupportedOperationException()
    }

    override fun getSystemServiceName(p0: Class<*>): String? {
        throw UnsupportedOperationException()
    }

    override fun getTheme(): Resources.Theme? {
        throw UnsupportedOperationException()
    }

    override fun getWallpaper(): Drawable? {
        throw UnsupportedOperationException()
    }

    override fun getWallpaperDesiredMinimumHeight(): Int {
        throw UnsupportedOperationException()
    }

    override fun getWallpaperDesiredMinimumWidth(): Int {
        throw UnsupportedOperationException()
    }

    override fun grantUriPermission(p0: String?, p1: Uri?, p2: Int): Unit {
        throw UnsupportedOperationException()
    }

    override fun isDeviceProtectedStorage(): Boolean {
        throw UnsupportedOperationException()
    }

    override fun moveDatabaseFrom(p0: Context?, p1: String?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun moveSharedPreferencesFrom(p0: Context?, p1: String?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun openFileInput(p0: String?): FileInputStream? {
        throw UnsupportedOperationException()
    }

    override fun openFileOutput(p0: String?, p1: Int): FileOutputStream? {
        throw UnsupportedOperationException()
    }

    override fun openOrCreateDatabase(p0: String?, p1: Int, p2: SQLiteDatabase.CursorFactory?): SQLiteDatabase? {
        throw UnsupportedOperationException()
    }

    override fun openOrCreateDatabase(p0: String?, p1: Int, p2: SQLiteDatabase.CursorFactory?, p3: DatabaseErrorHandler?): SQLiteDatabase? {
        throw UnsupportedOperationException()
    }

    override fun peekWallpaper(): Drawable? {
        throw UnsupportedOperationException()
    }

    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?): Intent? {
        throw UnsupportedOperationException()
    }

    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?, p2: Int): Intent? {
        throw UnsupportedOperationException()
    }

    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?, p2: String?, p3: Handler?): Intent? {
        throw UnsupportedOperationException()
    }

    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?, p2: String?, p3: Handler?, p4: Int): Intent? {
        throw UnsupportedOperationException()
    }

    override fun removeStickyBroadcast(p0: Intent?): Unit {
        throw UnsupportedOperationException()
    }

    override fun removeStickyBroadcastAsUser(p0: Intent?, p1: UserHandle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun revokeUriPermission(p0: Uri?, p1: Int): Unit {
        throw UnsupportedOperationException()
    }

    override fun revokeUriPermission(p0: String?, p1: Uri?, p2: Int): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendBroadcast(p0: Intent?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendBroadcast(p0: Intent?, p1: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendBroadcastAsUser(p0: Intent?, p1: UserHandle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendBroadcastAsUser(p0: Intent?, p1: UserHandle?, p2: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendOrderedBroadcast(p0: Intent?, p1: String?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendOrderedBroadcast(p0: Intent, p1: String?, p2: BroadcastReceiver?, p3: Handler?, p4: Int, p5: String?, p6: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendOrderedBroadcastAsUser(p0: Intent?, p1: UserHandle?, p2: String?, p3: BroadcastReceiver?, p4: Handler?, p5: Int, p6: String?, p7: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendStickyBroadcast(p0: Intent?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendStickyBroadcastAsUser(p0: Intent?, p1: UserHandle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendStickyOrderedBroadcast(p0: Intent?, p1: BroadcastReceiver?, p2: Handler?, p3: Int, p4: String?, p5: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun sendStickyOrderedBroadcastAsUser(p0: Intent?, p1: UserHandle?, p2: BroadcastReceiver?, p3: Handler?, p4: Int, p5: String?, p6: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun setTheme(p0: Int): Unit {
        throw UnsupportedOperationException()
    }

    override fun setWallpaper(p0: Bitmap?): Unit {
        throw UnsupportedOperationException()
    }

    override fun setWallpaper(p0: InputStream?): Unit {
        throw UnsupportedOperationException()
    }

    override fun startActivities(p0: Array<Intent?>): Unit {
        throw UnsupportedOperationException()
    }

    override fun startActivities(p0: Array<Intent?>, p1: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun startActivity(p0: Intent?): Unit {
        throw UnsupportedOperationException()
    }

    override fun startActivity(p0: Intent?, p1: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun startForegroundService(p0: Intent?): ComponentName? {
        throw UnsupportedOperationException()
    }

    override fun startInstrumentation(p0: ComponentName, p1: String?, p2: Bundle?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun startIntentSender(p0: IntentSender?, p1: Intent?, p2: Int, p3: Int, p4: Int): Unit {
        throw UnsupportedOperationException()
    }

    override fun startIntentSender(p0: IntentSender?, p1: Intent?, p2: Int, p3: Int, p4: Int, p5: Bundle?): Unit {
        throw UnsupportedOperationException()
    }

    override fun startService(p0: Intent?): ComponentName? {
        throw UnsupportedOperationException()
    }

    override fun stopService(p0: Intent?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun unbindService(p0: ServiceConnection): Unit {
        throw UnsupportedOperationException()
    }

    override fun unregisterReceiver(p0: BroadcastReceiver?): Unit {
        throw UnsupportedOperationException()
    }
    }

    /** `MethodChannel.Result` falso que registra invocaciones e hilo. */
    private class FakeResult {
        sealed interface Call {
            data class Success(val path: String?) : Call
            data class Error(
                val code: String,
                val message: String?,
                val details: Map<*, *>?,
            ) : Call
        }

        val calls = mutableListOf<Call>()
        val thread = AtomicReference<Thread?>(null)
        val latch = CountDownLatch(1)

        val result = object : MethodChannel.Result {
            override fun success(result: Any?) {
                synchronized(calls) {
                    calls += Call.Success(result as? String)
                }
                thread.set(Thread.currentThread())
                latch.countDown()
            }

            override fun error(
                errorCode: String,
                errorMessage: String?,
                errorDetails: Any?,
            ) {
                synchronized(calls) {
                    calls += Call.Error(
                        errorCode,
                        errorMessage,
                        errorDetails as? Map<*, *>,
                    )
                }
                thread.set(Thread.currentThread())
                latch.countDown()
            }

            override fun notImplemented() {
                thread.set(Thread.currentThread())
                latch.countDown()
            }
        }

        fun awaitReply(timeoutMs: Long = 10_000L): Boolean =
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun singleSuccess(): Call.Success {
            assertEquals(1, synchronized(calls) { calls.size })
            return synchronized(calls) { calls.single() } as Call.Success
        }

        fun singleError(): Call.Error {
            assertEquals(1, synchronized(calls) { calls.size })
            return synchronized(calls) { calls.single() } as Call.Error
        }
    }

    private lateinit var baseDir: File
    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: CoroutineDispatcher
    private lateinit var mainThread: Thread

    @Before
    fun setUp() {
        // `Transmiti2Logger` sin receptor llamaría a `android.util.Log`,
        // que lanza en las unitarias JVM puras.
        Transmiti2Logger.sink = { _, _ -> }
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "handler-recordings-dir-test-${System.nanoTime()}",
        )
        baseDir.mkdirs()
        mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "TestMain")
        }
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
        mainThread = mainExecutor.submit<Thread> {
            Thread.currentThread()
        }.get()
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
        mainExecutor.shutdownNow()
        baseDir.deleteRecursively()
    }

    private fun handler(
        filesDir: File?,
        scope: CoroutineScope,
    ): StreamChannelHandler {
        val history = object : PermissionRequestHistory {
            override fun wasRequested(kind: PermissionKind): Boolean = false
            override fun markRequested(kind: PermissionKind) {}
        }
        val permissionManager = PermissionManager(
            history,
            { false },
            { false },
        )
        val store = object : SecureCredentialStore {
            override suspend fun save(
                destinationId: Long,
                plaintext: ByteArray,
            ) {
                throw UnsupportedOperationException()
            }

            override suspend fun load(destinationId: Long): ByteArray? = null
            override suspend fun delete(destinationId: Long) {}
            override suspend fun has(destinationId: Long): Boolean = false
        }
        return StreamChannelHandler(
            context = FakeContext(filesDir),
            scope = scope,
            storeFactory = { store },
            permissionManager = permissionManager,
            textureRegistry = null,
            cameraManager = null,
            mainDispatcher = mainDispatcher,
        )
    }

    /**
     * `true` si el `lazy` de `previewEngine` ya se evaluó (aunque
     * devolviera `null`): si la rama lo tocara, estaría
     * inicializado. Es la única forma de observar el `lazy`
     * privado sin cambiar la producción.
     *
     * El campo se llama `previewEngineLazy` desde el grupo 2-c, que
     * guarda el `Lazy` aparte (antes era el delegado sintético
     * `previewEngine$delegate` de `by lazy`): los accesores de
     * diagnóstico lo consultan con `isInitialized()` sin
     * materializar el motor. La intención del test no cambia: el
     * motor no debe materializarse en esta rama.
     */
    private fun previewEngineInitialized(
        handler: StreamChannelHandler,
    ): Boolean {
        val field = StreamChannelHandler::class.java.getDeclaredField(
            "previewEngineLazy",
        )
        field.isAccessible = true
        return (field.get(handler) as Lazy<*>).isInitialized()
    }

    @Test
    fun `U53 handler con registros nulos devuelve la ruta sin crear el motor`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val handler = handler(baseDir, scope)
            val fake = FakeResult()
            handler.onMethodCall(
                MethodCall(
                    StreamContract.GET_RECORDINGS_DIR,
                    mapOf("attempt" to 1),
                ),
                fake.result,
            )
            assertTrue("la respuesta no llegó", fake.awaitReply())
            val success = fake.singleSuccess()
            assertEquals(
                File(baseDir, "recordings").absolutePath,
                success.path,
            )
            assertTrue(File(baseDir, "recordings").isDirectory)
            // Respuesta en Main (dispatcher falso), por identidad
            // de hilo: el hilo del ejecutor TestMain, nunca el del
            // test (que llamó a onMethodCall).
            val replyThread = fake.thread.get()
            assertNotNull(replyThread)
            assertEquals(mainThread, replyThread)
            assertNotEquals(Thread.currentThread(), replyThread)
            // previewEngine no se materializó.
            assertFalse(previewEngineInitialized(handler))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `U53 handler con filesDir nulo devuelve context_null sin crear el motor`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val handler = handler(null, scope)
            val fake = FakeResult()
            handler.onMethodCall(
                MethodCall(
                    StreamContract.GET_RECORDINGS_DIR,
                    mapOf("attempt" to 1),
                ),
                fake.result,
            )
            assertTrue("la respuesta no llegó", fake.awaitReply())
            val error = fake.singleError()
            assertEquals(
                StreamContract.RECORDINGS_DIR_UNAVAILABLE,
                error.code,
            )
            assertEquals("context_null", error.details?.get("stage"))
            assertFalse(previewEngineInitialized(handler))
        } finally {
            scope.cancel()
        }
    }
}
