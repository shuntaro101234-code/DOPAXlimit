package com.example.dopax_limit

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import android.app.admin.DevicePolicyManager

class LimitMonitorService : Service() {
    private var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null

    // 🟢 【超重要】すでに画面ロックが起動中かどうかを記録するフラグ
    private var isCurrentlyLocked = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "limit_channel")
            .setContentTitle("DOPAX limit 稼働中")
            .setContentText("安全な利用を見守っています。")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1, notification)

        val familyDocId = intent?.getStringExtra("familyDocId") ?: ""
        if (familyDocId.isNotEmpty()) {
            startListening(familyDocId)
        }
        return START_STICKY
    }

    private fun startListening(docId: String) {
        firestoreListener = FirebaseFirestore.getInstance().collection("families").document(docId)
            .addSnapshotListener { snapshot, _ ->
                val isLimited = snapshot?.getBoolean("isLimited") ?: false

                // 🟢 【追加】：Firestoreから禁止URLを取得して、Chromeにリアルタイム適用
                val blockedUrl = snapshot?.getString("blockedUrl") ?: ""
                setWebBlocklist(blockedUrl)

                if (isLimited) {
                    if (!isCurrentlyLocked) {
                        isCurrentlyLocked = true
                        setAppsSuspended(true)
                        val lockIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(lockIntent)
                    }
                } else {
                    if (isCurrentlyLocked) {
                        isCurrentlyLocked = false
                        setAppsSuspended(false)
                    }
                }
            }
    }


    private fun setAppsSuspended(suspend: Boolean) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0).map { it.packageName }.toMutableList()
            packages.add("com.android.chrome")
            packages.add("com.sec.android.app.sbrowser")
            packages.add("com.google.android.youtube")
            val finalPackages = packages.filter { pkg ->
                pkg != packageName && pkg != "com.android.launcher3"
            }.distinct().toTypedArray()
            try { dpm.setPackagesSuspended(adminName, finalPackages, suspend) } catch (e: Exception) {}
        }
    }

    // 🟢 【追加】：親が保存した禁止URLをChromeに送り込んで強制ブロックする関数
    private fun setWebBlocklist(blockedUrl: String) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            val restrictions = android.os.Bundle().apply {
                if (blockedUrl.isNotEmpty()) {
                    // Chromeに「URLBlocklist（閲覧禁止）」を送信
                    putStringArray("URLBlocklist", arrayOf(blockedUrl))
                } else {
                    // 空なら制限解除
                    putStringArray("URLBlocklist", emptyArray())
                }
            }
            try {
                dpm.setApplicationRestrictions(adminName, "com.android.chrome", restrictions)
                Log.d("DOPAX", "Chrome制限適用成功: $blockedUrl")
            } catch (e: Exception) {
                Log.e("DOPAX", "Chrome制限適用失敗: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel("limit_channel", "Limit Monitor", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        firestoreListener?.remove()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
