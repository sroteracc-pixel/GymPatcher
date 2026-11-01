package com.seb.gympatcher

import android.Manifest
import android.content.Intent
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
import androidx.core.content.FileProvider
import java.io.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val GAME_PKG      = "com.IRLStudios.GymClass"
    private val OBB_DIR       = "/sdcard/Android/obb/$GAME_PKG"
    private val OBB_BAK       = "/sdcard/GymPatcherBackup/obb"
    private val MOD_APK_ASSET = "GymClass-modded.apk"
    private val REQ_STORAGE   = 1001

    private val executor = Executors.newSingleThreadExecutor()
    private val main     = Handler(Looper.getMainLooper())

    private lateinit var statusText    : TextView
    private lateinit var btnStep1      : Button
    private lateinit var btnStep2      : Button
    private lateinit var btnStep3      : Button
    private lateinit var btnUninstall  : Button

    private var pendingAction: (() -> Unit)? = null

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

        checkStorage { showReady() }
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
            "  Step 2 — Installs the modded version\n" +
            "            (tap Install on the popup that appears)\n" +
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

    // ── STEP 2: Install modded APK via system installer ─────────────────────
    private fun doStep2() {
        btnStep2.isEnabled = false
        log("Extracting modded APK...")
        val outFile = File(filesDir, MOD_APK_ASSET)
        try {
            assets.open(MOD_APK_ASSET).use { inp ->
                outFile.outputStream().use { out -> inp.copyTo(out) }
            }
        } catch (e: Exception) {
            log("Mod APK missing from app bundle. The app needs to be rebuilt.")
            btnStep2.isEnabled = true
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        log("Installer popup appeared!\n\nTap INSTALL, wait for it to finish, then come back and tap Step 3.")
        btnStep2.isEnabled = true
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
