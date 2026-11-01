package com.seb.gympatcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val GAME_PKG      = "com.IRLStudios.GymClass"
    private val OBB_DIR       = "/sdcard/Android/obb/$GAME_PKG"
    private val OBB_BAK       = "/sdcard/GymPatcherBackup/obb"
    private val MOD_APK_ASSET = "GymClass-modded.apk"
    private val REQ_STORAGE   = 1001
    private val ACTION_INSTALL_RESULT = "com.seb.gympatcher.INSTALL_RESULT"

    private val executor = Executors.newSingleThreadExecutor()
    private val main     = Handler(Looper.getMainLooper())

    private lateinit var statusText    : TextView
    private lateinit var btnStep1      : Button
    private lateinit var btnStep2      : Button
    private lateinit var btnStep3      : Button
    private lateinit var btnUninstall  : Button

    private var pendingAction: (() -> Unit)? = null

    // Receives the PackageInstaller result broadcast
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val msg    = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    log("Modded GymClass installed!\n\nTap Step 3 to restore your game data and open GymClass.")
                    btnStep2.isEnabled = true
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Shouldn't happen without root, but handle it gracefully
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent != null) startActivity(confirmIntent)
                }
                else -> {
                    log("Install failed (code $status): $msg\n\nMake sure the original GymClass is uninstalled first (Step 1), then try Step 2 again.")
                    btnStep2.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.statusText)
        btnStep1     = findViewById(R.id.btnStep1)
        btnStep2     = findViewById(R.id.btnStep2)
        btnStep3     = findViewById(R.id.btnStep3)
        btnUninstall = findViewById(R.id.btnUninstall)

        btnStep1.setOnClickListener     { checkStorage { doStep1() } }
        btnStep2.setOnClickListener     { checkStorage { doStep2() } }
        btnStep3.setOnClickListener     { checkStorage { doStep3() } }
        btnUninstall.setOnClickListener { checkStorage { doUninstall() } }

        // Register broadcast receiver for install result
        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }

        checkStorage { showReady() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installReceiver) } catch (_: Exception) {}
    }

    // ── Storage permission ──────────────────────────────────────────────────
    private fun checkStorage(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                log("GymPatcher needs storage access to back up your game data.\n\nOn the next screen, turn on 'Allow access to manage all files'.")
                pendingAction = onGranted
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")),
                    REQ_STORAGE
                )
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingAction = onGranted
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE
                )
                return
            }
        }
        onGranted()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                pendingAction?.invoke(); pendingAction = null
            } else {
                log("Storage permission required. Tap a button to try again.")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && grantResults.isNotEmpty()
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAction?.invoke(); pendingAction = null
        } else {
            log("Storage permission required. Tap a button to try again.")
        }
    }

    private fun showReady() {
        log(
            "Ready!\n\n" +
            "How to mod GymClass:\n\n" +
            "  Step 1 — Saves your game data and removes GymClass\n" +
            "  Step 2 — Installs the modded version automatically\n" +
            "  Step 3 — Restores your game data and opens GymClass\n\n" +
            "Tap STEP 1 to begin"
        )
    }

    // ── STEP 1: Back up OBB + uninstall original ────────────────────────────
    private fun doStep1() {
        btnStep1.isEnabled = false
        log("Step 1: Saving your game data. Please wait...")
        executor.execute {
            try {
                // Check OBB exists
                if (!File(OBB_DIR).exists()) {
                    ui {
                        log("No game data found.\n\nOpen GymClass, let it finish downloading, then try Step 1 again.")
                        btnStep1.isEnabled = true
                    }
                    return@execute
                }
                // Check GymClass is installed
                try { packageManager.getPackageInfo(GAME_PKG, 0) }
                catch (e: PackageManager.NameNotFoundException) {
                    ui {
                        log("GymClass isn't installed.\n\nInstall it from the Meta store first.")
                        btnStep1.isEnabled = true
                    }
                    return@execute
                }
                // Back up OBB
                val bakDir = File("$OBB_BAK/$GAME_PKG")
                bakDir.deleteRecursively()
                if (!copyDir(File(OBB_DIR), bakDir)) {
                    ui {
                        log("Couldn't save game data. Free up storage and try Step 1 again.")
                        btnStep1.isEnabled = true
                    }
                    return@execute
                }
                // Uninstall via system popup
                ui {
                    log("Game data saved!\n\nTap UNINSTALL on the popup to remove the original GymClass.\nThen come back here and tap Step 2.")
                    btnStep1.isEnabled = true
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$GAME_PKG")))
                }
            } catch (e: Exception) {
                ui { log("Error: ${e.message}"); btnStep1.isEnabled = true }
            }
        }
    }

    // ── STEP 2: Silent install via PackageInstaller.Session ─────────────────
    private fun doStep2() {
        btnStep2.isEnabled = false
        log("Installing modded GymClass... please wait.")
        executor.execute {
            try {
                // Extract APK from assets to internal storage
                val apkFile = File(filesDir, MOD_APK_ASSET)
                assets.open(MOD_APK_ASSET).use { inp ->
                    apkFile.outputStream().use { out -> inp.copyTo(out) }
                }

                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                params.setAppPackageName(GAME_PKG)

                val sessionId = installer.createSession(params)
                val session   = installer.openSession(sessionId)

                // Write APK bytes into the session
                apkFile.inputStream().use { apkIn ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { sessionOut ->
                        apkIn.copyTo(sessionOut)
                        session.fsync(sessionOut)
                    }
                }

                // PendingIntent that broadcasts back to our receiver
                val resultIntent = Intent(ACTION_INSTALL_RESULT).apply {
                    setPackage(packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                else
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT

                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    this@MainActivity, sessionId, resultIntent, flags
                )

                session.commit(pendingIntent.intentSender)
                session.close()

                // APK extracted; receiver will update UI on completion
                ui { log("Installing... this may take 10–20 seconds.") }

            } catch (e: Exception) {
                ui {
                    log("Install failed: ${e.message}\n\nMake sure GymClass is fully uninstalled (Step 1) and try again.")
                    btnStep2.isEnabled = true
                }
            }
        }
    }

    // ── STEP 3: Restore OBB + launch ────────────────────────────────────────
    private fun doStep3() {
        btnStep3.isEnabled = false
        log("Step 3: Restoring your game data...")
        executor.execute {
            try {
                val bakSrc = File("$OBB_BAK/$GAME_PKG").let {
                    if (it.exists()) it else File(OBB_BAK)
                }
                if (!bakSrc.exists()) {
                    ui { log("No backup found. Run Step 1 first."); btnStep3.isEnabled = true }
                    return@execute
                }
                val destDir = File(OBB_DIR)
                destDir.deleteRecursively()
                if (!copyDir(bakSrc, destDir)) {
                    ui {
                        log("Couldn't restore game data.\n\nMake sure GymPatcher has 'All Files Access' in Settings.")
                        btnStep3.isEnabled = true
                    }
                    return@execute
                }
                File(OBB_BAK).deleteRecursively()
                ui {
                    log("All done! GymClass is now modded.\n\nOpening the game...")
                    btnStep3.isEnabled = true
                    launchGame()
                }
            } catch (e: Exception) {
                ui { log("Error: ${e.message}"); btnStep3.isEnabled = true }
            }
        }
    }

    // ── UNINSTALL ────────────────────────────────────────────────────────────
    private fun doUninstall() {
        btnUninstall.isEnabled = false
        log("Clearing GymClass data...")
        executor.execute {
            try {
                File(OBB_DIR).deleteRecursively()
                File(OBB_BAK).deleteRecursively()
                ui {
                    log("Game data cleared. Tap UNINSTALL on the popup.")
                    btnUninstall.isEnabled = true
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$GAME_PKG")))
                }
            } catch (e: Exception) {
                ui { log("Error: ${e.message}"); btnUninstall.isEnabled = true }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun copyDir(src: File, dst: File): Boolean {
        return try {
            dst.mkdirs()
            src.walkTopDown().forEach { f ->
                val dest = File(dst, f.relativeTo(src).path)
                if (f.isDirectory) dest.mkdirs() else f.copyTo(dest, overwrite = true)
            }
            true
        } catch (e: Exception) { false }
    }

    private fun launchGame() {
        packageManager.getLaunchIntentForPackage(GAME_PKG)
            ?.let { startActivity(it) }
            ?: log("Open GymClass from your library.")
    }

    private fun log(msg: String) = main.post { statusText.text = msg }
    private fun ui(block: () -> Unit) = main.post(block)
}
