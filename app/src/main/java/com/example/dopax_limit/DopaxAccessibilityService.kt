package com.example.dopax_limit

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class DopaxAccessibilityService : AccessibilityService() {

    private var firestoreListener: ListenerRegistration? = null
    private var isCurrentlyLocked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 💡 端末に保存されたIDを取得してFirestore監視をスタート
        val prefs = getSharedPreferences("dopax_prefs", MODE_PRIVATE)
        val familyDocId = prefs.getString("family_doc_id", "") ?: ""
        if (familyDocId.isNotEmpty()) {
            startListening(familyDocId)
        }
    }

    private fun startListening(docId: String) {
        firestoreListener = FirebaseFirestore.getInstance().collection("families").document(docId)
            .addSnapshotListener { snapshot, _ ->
                isCurrentlyLocked = snapshot?.getBoolean("isLimited") ?: false
            }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val launchedApp = event.packageName?.toString() ?: return

            // 🟢 制限（ロック）中、かつ開いたアプリが「自分自身」でも「ホームアプリ」でもない場合
            if (isCurrentlyLocked && launchedApp != packageName && launchedApp != "com.android.launcher3") {
                // 💡 他のアプリ（Chrome等）を画面の裏に押し戻し、ロック画面を最前面にねじ込みます
                val lockIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(lockIntent)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        firestoreListener?.remove()
        super.onDestroy()
    }
}
