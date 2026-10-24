package dev.leyline.app

import android.app.Application
import android.app.NotificationManager
import dev.leyline.app.data.LeylineDatabase
import dev.leyline.app.data.MessageRepository
import dev.leyline.app.data.Settings
import dev.leyline.app.mesh.BoxKeys
import dev.leyline.app.mesh.KeystoreSigner
import dev.leyline.app.mesh.MeshController
import dev.leyline.app.mesh.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: every long-lived object is built here, once, in
 * dependency order. What Hilt would generate, written by hand, so it's obvious
 * who owns what and nothing is created twice.
 */
class AppContainer(app: Application) {
    /** Lives as long as the process. SupervisorJob: one failed child doesn't cancel the rest. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** This install's identity. Created once, in secure hardware; the node id derives from it. */
    val signer = KeystoreSigner.loadOrCreate()
    val settings = Settings(app, signer.nodeId)
    val database = LeylineDatabase.create(app)
    val repository = MessageRepository(database)
    val notifier = Notifier(app)
    val notificationManager: NotificationManager = app.getSystemService(NotificationManager::class.java)
    val boxKeys = BoxKeys.loadOrCreate(app)
    val mesh = MeshController(app, settings, signer, boxKeys, repository, database, notifier, appScope)

    /** One-tap SOS from the Quick Settings tile. */
    fun sendQuickSos() {
        appScope.launch { mesh.send(QUICK_SOS_TEXT, sos = true) }
    }

    companion object {
        const val QUICK_SOS_TEXT = "SOS: I need help. Sent from Quick Settings."
    }
}

class LeylineApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
