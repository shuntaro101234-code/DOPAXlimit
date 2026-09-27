package com.example.dopax_limit

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.delay
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.net.Uri
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.clickable










class MainActivity : FragmentActivity() {

    private lateinit var auth: FirebaseAuth

    private var pairingCode by mutableStateOf("")
    private var statusMessage by mutableStateOf("DOPAX limit へお越しいただきありがとうございます。")
    private var myUid by mutableStateOf("")
    private var selectedRole by mutableStateOf("")

    private var familyDocId by mutableStateOf("")
    private var isLimited by mutableStateOf(false)
    private var startTime by mutableStateOf("21:00")
    private var endTime by mutableStateOf("07:00")
    private var dailyLimitMinutes by mutableStateOf(60)
    private var usedSecondsToday by mutableStateOf(0)
    private var allLimitsOff by mutableStateOf(false)
    private var parentPin by mutableStateOf("1234")

    private var childLat by mutableStateOf(0.0)
    private var childLng by mutableStateOf(0.0)
    private var pendingAppRequest by mutableStateOf("")
    private var blockedUrl by mutableStateOf("")
    private var appApprovalStatus by mutableStateOf("")
    private var isAppLocked by mutableStateOf(false)

    fun setAppsSuspended(suspend: Boolean) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0).map { it.packageName }.filter { pkg ->
                pkg != packageName && pkg != "com.android.launcher3"
            }.toTypedArray()

            try {
                dpm.setPackagesSuspended(adminName, packages, suspend)
            } catch (e: Exception) {
                Log.e("DOPAX", "サスペンド失敗: ${e.message}")
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth

        // 🌟 自アプリを「Lock Task」の許可リストに登録、およびセキュリティ盲点の完全封鎖
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            // 本物のキオスク登録
            dpm.setLockTaskPackages(adminName, arrayOf(packageName))

            // 🟢 【盲点対策①：設定画面からの強制初期化（リセット）を禁止】
            dpm.addUserRestriction(adminName, android.os.UserManager.DISALLOW_FACTORY_RESET)

            // 🟢 【裏技対策：セーフモード起動での制限回避を100%禁止】
            dpm.addUserRestriction(adminName, android.os.UserManager.DISALLOW_SAFE_BOOT)

            // 🟢 【盲点対策②：端末の日付・時刻設定の手動変更を禁止】
            dpm.addUserRestriction(adminName, android.os.UserManager.DISALLOW_CONFIG_DATE_TIME)

            // 🟢 【盲点対策③：ネットワークからの自動時刻同期を強制（時計戻しハックの防止）】
            dpm.setAutoTimeRequired(adminName, true)
        }

        // ★ すでにログイン済みの場合は状態を自動復元
        auth.currentUser?.let {
            myUid = it.uid
            checkExistingPairing(myUid)
        }

        loadLocalUsage()
        selectedRole = loadLocalRole()

        // 🌟 他のアプリの上に重ねて表示する権限をチェック・要求する
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                val overlayIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                // 設定画面を直接開く
                startActivity(overlayIntent)
                statusMessage = "⚠️ 「他のアプリの上に重ねて表示」を許可してください！"
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    fun MainScreen() {

        var emailInput by rememberSaveable { mutableStateOf("") }
        var passwordInput by rememberSaveable { mutableStateOf("") }

        var inputCode by remember { mutableStateOf("") }
        var startInput by remember { mutableStateOf(startTime) }
        var endInput by remember { mutableStateOf(endTime) }
        var limitInput by remember { mutableStateOf(dailyLimitMinutes.toString()) }
        var webLimitInput by remember { mutableStateOf(blockedUrl) }
        var appDownloadInput by remember { mutableStateOf("") }

        var childEmailInput by remember { mutableStateOf("") }
        var childPasswordInput by remember { mutableStateOf("") }

        val lifecycleOwner = LocalLifecycleOwner.current
        var isAppResumed by remember { mutableStateOf(true) }


        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                isAppResumed = (event == Lifecycle.Event.ON_RESUME)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val isTimeRestricted =
            remember(startTime, endTime) { isCurrentTimeInRestrictedRange(startTime, endTime) }
        val isDurationOver = usedSecondsToday >= (dailyLimitMinutes * 60)
        val finalLimited =
            if (allLimitsOff || familyDocId.isEmpty()) false
            else (isLimited || isTimeRestricted || isDurationOver)


// ログアウトしてmyUidが空になったら、フォームをリセットする
        LaunchedEffect(myUid) {
            if (myUid.isEmpty()) {
                emailInput = ""
                passwordInput = ""
            }
        }

        LaunchedEffect(finalLimited) {
            if (selectedRole == "child") {
                if (finalLimited) {
                    setAppsSuspended(true) // 他のアプリを一時停止（グレーアウト）

                    // 💡 無限再起動（チカチカ）ループを防止するため、ここでの「startActivity」は削除しました。
                    // 💡 自アプリ（MainActivity）はすでに開いているため、これだけで安全にロック（画面固定）されます。
                    startLockTask()
                } else {
                    setAppsSuspended(false) // 制限解除で、他アプリの一時停止を解く
                    try {
                        stopLockTask() // 自アプリのピン留めを解除
                    } catch (e: Exception) {
                        Log.e("DOPAX", "ロック解除に失敗しました: ${e.message}")
                    }
                }
            }
        }



        LaunchedEffect(selectedRole, finalLimited, isAppResumed) {
            if (selectedRole == "child" && !finalLimited && isAppResumed) {
                while (true) {
                    delay(1000)
                    usedSecondsToday += 1
                    saveLocalUsage(usedSecondsToday)
                }
            }
        }
        LaunchedEffect(selectedRole, familyDocId) {
            if (selectedRole == "child" && familyDocId.isNotEmpty()) {
                val serviceIntent = Intent(this@MainActivity, LimitMonitorService::class.java).apply {
                    putExtra("familyDocId", familyDocId)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.my_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "DOPAX limit",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("状況：\n$statusMessage", style = MaterialTheme.typography.bodyLarge)

                Spacer(modifier = Modifier.height(4.dp))

                if (myUid.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "🔒 アカウント ログイン / 新規登録",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("メールアドレス") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("パスワード") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { loginUser(emailInput, passwordInput) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("メールアドレスでログイン") }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { registerUser(emailInput, passwordInput) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("新規アカウントを作成して登録") }
                        }
                    }
                } else if (isAppLocked) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "🔒 画面がロックされています",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (selectedRole == "parent") {
                                Button(
                                    onClick = {
                                        showBiometricPrompt {
                                            isAppLocked = false; statusMessage = "生体認証成功！"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("🧬 親の生体認証（指紋・顔）で解除") }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { logoutUser() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("🚪 アカウント切り替え（ログアウト）") }
                        }
                    }
                } else if (selectedRole.isEmpty()) {
                    Text("あなたの役割を選んでね", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        selectedRole = "parent"; startListeningToFamilyAsParent(myUid)
                    }, modifier = Modifier.fillMaxWidth()) { Text("👨‍👩‍👧 親として使う") }
                    Button(
                        onClick = { selectedRole = "child" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("👶 子どもとして使う") }
                } else if (selectedRole == "parent") {
                    ParentView(
                        startInput = startInput,
                        endInput = endInput,
                        limitInput = limitInput,
                        webLimitInput = webLimitInput,
                        onStartChange = { startInput = it },
                        onEndChange = { endInput = it },
                        onLimitChange = { limitInput = it },
                        onWebChange = { webLimitInput = it }
                    )
                } else if (selectedRole == "child") {
                    // ローカル保存されているロックモード（lock_mode）を読み込みます
                    val prefs =
                        androidx.compose.ui.platform.LocalContext.current.getSharedPreferences(
                            "dopax_prefs",
                            MODE_PRIVATE
                        )
                    var lockMode by remember { mutableStateOf(prefs.getString("lock_mode", "")) }

                    if (lockMode.isNullOrEmpty()) {
                        // モードがまだ未選択の場合は、選択画面を表示
                        ModeSelectionScreen { selectedMode ->
                            prefs.edit().putString("lock_mode", selectedMode).apply()
                            lockMode = selectedMode
                        }
                    } else {
                        // 選択済みの場合は通常の子ども画面を表示
                        ChildView(
                            finalLimited,
                            isDurationOver,
                            isTimeRestricted,
                            inputCode,
                            appDownloadInput,
                            childEmailInput,
                            childPasswordInput,
                            onCodeChange = { inputCode = it },
                            onAppChange = { appDownloadInput = it },
                            onChildEmailChange = { childEmailInput = it },
                            onChildPasswordChange = { childPasswordInput = it }
                        )
                    }
                }
            }
        }
    }
    @Composable
    private fun ParentView(
        startInput: String,
        endInput: String,
        limitInput: String,
        webLimitInput: String,
        onStartChange: (String) -> Unit,
        onEndChange: (String) -> Unit,
        onLimitChange: (String) -> Unit,
        onWebChange: (String) -> Unit
    ) {
        var pinInput by remember { mutableStateOf(parentPin) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { logoutUser() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🚪 ログアウト") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("🛡️ 使用可能状態")
                    Switch(
                        checked = allLimitsOff,
                        onCheckedChange = { toggleAllLimitsOff() })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("🚨 使用不可能状態")
                    Switch(checked = isLimited, onCheckedChange = { toggleLimit() })
                }
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("PIN (4桁)") })
                Button(onClick = { saveParentPin(pinInput) }) { Text("PIN保存") }
                OutlinedTextField(
                    value = webLimitInput,
                    onValueChange = onWebChange,
                    label = { Text("ブラウザ制限 URL") })
                Button(onClick = { saveWebLimit(webLimitInput) }) { Text("制限保存") }
                OutlinedTextField(
                    value = limitInput,
                    onValueChange = onLimitChange,
                    label = { Text("1日上限 (分)") })
                Button(onClick = {
                    saveDailyLimit(
                        limitInput.toIntOrNull() ?: 60
                    )
                }) { Text("上限保存") }
                Row {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = onStartChange,
                        label = { Text("開始") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = onEndChange,
                        label = { Text("終了") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(onClick = {
                    saveTimeLimit(
                        startInput,
                        endInput
                    )
                }) { Text("時間保存") }
                // ... 既存のコード ...
                Button(onClick = { createPairingCode(myUid) }) { Text("コード発行") }
                if (pairingCode.isNotEmpty()) Text(
                    "コード: $pairingCode",
                    style = MaterialTheme.typography.titleLarge
                )

                // 🟢 【ここに以下のコードを丸ごと追加してください！】
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "🛡️ 親御様へ：突破を防ぐ重要セキュリティ推奨ルール",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "1️⃣ PIN（暗証番号）ののぞき見防止\n" +
                                    "設定したPINコード（暗証番号）の入力時、お子様にのぞき見されないよう注意してください。定期的に暗証番号を変更することが最も安全な対策です。\n\n" +
                                    "2️⃣ 管理用（親）端末のパスコード管理\n" +
                                    "お子様が親御様のスマートフォンの「画面ロック解除パスコード」を知っている場合、こっそりお子様の指紋を追加登録されて、生体認証を突破される恐れがあります。ロック解除用パスコードは絶対に共有しないようにしてください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } // Column の閉じ括弧
        } // Card の閉じ括弧
    } // ParentView の閉じ括弧

    @Composable
    private fun ChildView(
        finalLimited: Boolean,
        isDurationOver: Boolean,
        isTimeRestricted: Boolean,
        inputCode: String,
        appDownloadInput: String,
        childEmailInput: String,
        childPasswordInput: String,
        onCodeChange: (String) -> Unit,
        onAppChange: (String) -> Unit,
        onChildEmailChange: (String) -> Unit,
        onChildPasswordChange: (String) -> Unit
    ) {
        if (finalLimited) {
            Box(
                modifier = Modifier.fillMaxWidth().height(350.dp).background(Color.Red.copy(alpha = 0.95f)).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("⚠️ DOPAX limit", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("制限時間をオーバーしたため画面をロックしています。", color = Color.White)

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("【緊急発信】", color = Color.Yellow, fontWeight = FontWeight.Bold)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:110")))
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                            Text("🚨 110番")
                        }
                        Button(onClick = {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:119")))
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                            Text("🚑 119番")
                        }
                    }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("👶 子どもの操作画面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    val remainingSeconds = (dailyLimitMinutes * 60) - usedSecondsToday
                    val remMin = remainingSeconds / 60
                    val remSec = remainingSeconds % 60
                    Text(
                        text = "本日の残り時間: $remMin 分 $remSec 秒",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF1E88E5),
                        fontWeight = FontWeight.Bold
                    )

                    Divider()

                    if (familyDocId.isEmpty()) {
                        OutlinedTextField(
                            value = inputCode,
                            onValueChange = onCodeChange,
                            label = { Text("6桁のコードを入力") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { verifyPairingCode(inputCode, myUid) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("送信してペアリング") }
                    } else {
                        Text("✅ お母さんのスマホと接続中", color = Color(0xFF25D366), fontWeight = FontWeight.Bold)

                        if (blockedUrl.isNotEmpty()) {
                            Text("🔒 閲覧禁止ウェブサイト: $blockedUrl", color = Color.Red, fontWeight = FontWeight.Bold)
                            Divider()
                        }

                        Text("📧 子どものアカウント保護（メール紐付け）", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(value = childEmailInput, onValueChange = onChildEmailChange, label = { Text("子どもメールアドレス") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = childPasswordInput, onValueChange = onChildPasswordChange, label = { Text("パスワード") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(onClick = { linkEmail(childEmailInput, childPasswordInput) }, modifier = Modifier.fillMaxWidth()) { Text("アカウントを紐付ける") }
                    }
                }
            }
        }
    }


    private fun registerUser(emailInput: String, passInput: String) {
                                if (emailInput.isEmpty() || passInput.isEmpty()) {
                                    statusMessage = "メールとパスワードを入力してください。"
                                    return
                                }
                                auth.createUserWithEmailAndPassword(emailInput, passInput)
                                    .addOnSuccessListener {
                                        myUid = auth.currentUser?.uid ?: ""
                                        statusMessage = "新規アカウントを登録し、ログインしました！"
                                        checkExistingPairing(myUid)
                                    }
                                    .addOnFailureListener { statusMessage = "登録失敗: ${it.message}" }
                            }

    private fun loginUser(email: String, pass: String) {
        if (email.isEmpty() || pass.isEmpty()) {
            statusMessage = "メールとパスワードを入力してください。"; return
        }
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener {
                showBiometricPrompt(
                    onSuccess = {
                        myUid = auth.currentUser?.uid ?: ""
                        statusMessage = "ログイン成功！"
                        checkExistingPairing(myUid)
                    },
                    onFailure = {
                        auth.signOut() // 顔認証が失敗・キャンセルされた場合はログインを無効化
                        statusMessage = "生体認証が拒否されたため、ログインを中止しました。"
                    }
                )
            }
            .addOnFailureListener { statusMessage = "ログイン失敗: ${it.message}" }
    }


    private fun signInAnonymously() {
                                if (auth.currentUser == null) {
                                    auth.signInAnonymously().addOnCompleteListener(this) { task ->
                                        if (task.isSuccessful) {
                                            myUid = auth.currentUser?.uid ?: ""
                                            checkExistingPairing(myUid)
                                        }
                                    }
                                }
                            }

                                    private fun setupFamilyData(doc: com.google.firebase.firestore.DocumentSnapshot, role: String) {
                                familyDocId = doc.id
                                isLimited = doc.getBoolean("isLimited") ?: false
                                startTime = doc.getString("startTime") ?: "21:00"
                                endTime = doc.getString("endTime") ?: "07:00"
                                dailyLimitMinutes = doc.getLong("dailyLimitMinutes")?.toInt() ?: 60
                                allLimitsOff = doc.getBoolean("allLimitsOff") ?: false
                                parentPin = doc.getString("parentPin") ?: "1234"
                                childLat = doc.getDouble("childLat") ?: 0.0
                                childLng = doc.getDouble("childLng") ?: 0.0
                                pendingAppRequest = doc.getString("pendingAppRequest") ?: ""
                                blockedUrl = doc.getString("blockedUrl") ?: ""
                                appApprovalStatus = doc.getString("appApprovalStatus") ?: ""
                                selectedRole = role
                                saveLocalFamilyDocId(doc.id)
                            }

                                    private fun checkExistingPairing(uid: String) {
                                val db = Firebase.firestore
                                db.collection("families").whereEqualTo("parentId", uid).get()
                                    .addOnSuccessListener { parentQuery ->
                                        if (!parentQuery.isEmpty) {
                                            setupFamilyData(parentQuery.documents.first(), "parent")
                                            isAppLocked = true
                                            statusMessage = "自動ログイン（親）がロックされています。"
                                            startListeningToFamilyAsParent(uid)
                                        } else {
                                            db.collection("families").whereEqualTo("childId", uid).get()
                                                .addOnSuccessListener { childQuery ->
                                                    if (!childQuery.isEmpty) {
                                                        setupFamilyData(childQuery.documents.first(), "child")
                                                        statusMessage = "自動ログイン成功（子ども）"
                                                        startListeningToLimit(familyDocId)
                                                    } else {
                                                        selectedRole = ""
                                                        statusMessage = "ログイン完了。役割を選択してね。"
                                                    }
                                                }
                                        }
                                    }
                            }

                                    private fun startListeningToFamilyAsParent(parentUid: String) {
                                if (parentUid.isEmpty()) return
                                val db = Firebase.firestore
                                db.collection("families").whereEqualTo("parentId", parentUid)
                                    .addSnapshotListener { snapshot, e ->
                                        if (e != null || snapshot == null || snapshot.isEmpty) return@addSnapshotListener
                                        setupFamilyData(snapshot.documents.first(), "parent")
                                        statusMessage = "✅ 子ども端末と接続中"
                                    }
                            }

                                    private fun startListeningToLimit(docId: String) {
                                val db = Firebase.firestore
                                db.collection("families").document(docId).addSnapshotListener { snapshot, e ->
                                    if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                                    isLimited = snapshot.getBoolean("isLimited") ?: false
                                    startTime = snapshot.getString("startTime") ?: "21:00"
                                    endTime = snapshot.getString("endTime") ?: "07:00"
                                    dailyLimitMinutes = snapshot.getLong("dailyLimitMinutes")?.toInt() ?: 60
                                    allLimitsOff = snapshot.getBoolean("allLimitsOff") ?: false
                                    parentPin = snapshot.getString("parentPin") ?: "1234"
                                    childLat = snapshot.getDouble("childLat") ?: 0.0
                                    childLng = snapshot.getDouble("childLng") ?: 0.0
                                    pendingAppRequest = snapshot.getString("pendingAppRequest") ?: ""
                                    blockedUrl = snapshot.getString("blockedUrl") ?: ""
                                    appApprovalStatus = snapshot.getString("appApprovalStatus") ?: ""
                                }
                            }
                                    private fun toggleLimit() {
                                if (familyDocId.isEmpty()) {
                                    statusMessage = "❌ ペアリングを完了してください。"; return
                                }
                                val db = Firebase.firestore
                                val nextState = !isLimited
                                db.collection("families").document(familyDocId).update("isLimited", nextState)
                                    .addOnSuccessListener {
                                        isLimited = nextState; statusMessage = if (isLimited) "🚨制限中" else "🔓解除中"
                                    }
                            }

                                    private fun toggleAllLimitsOff() {
                                if (familyDocId.isEmpty()) {
                                    statusMessage = "❌ ペアリングを完了してください。"; return
                                }
                                val db = Firebase.firestore
                                val nextState = !allLimitsOff
                                db.collection("families").document(familyDocId)
                                    .update(mapOf("allLimitsOff" to nextState, "isLimited" to false))
                                    .addOnSuccessListener {
                                        allLimitsOff = nextState; isLimited = false; statusMessage =
                                        if (nextState) "🛡️一時オフ" else "🔒再適用"
                                    }
                            }

                                    private fun saveTimeLimit(start: String, end: String) {
                                if (familyDocId.isEmpty()) {
                                    statusMessage = "❌ ペアリングを完了してください。"; return
                                }
                                Firebase.firestore.collection("families").document(familyDocId)
                                    .update(mapOf("startTime" to start, "endTime" to end))
                                    .addOnSuccessListener {
                                        startTime = start; endTime = end; statusMessage = "制限時刻を保存！"
                                    }
                            }

                                    private fun saveDailyLimit(minutes: Int) {
                                if (familyDocId.isEmpty()) {
                                    statusMessage = "❌ ペアリングを完了してください。"; return
                                }
                                Firebase.firestore.collection("families").document(familyDocId)
                                    .update("dailyLimitMinutes", minutes)
                                    .addOnSuccessListener {
                                        dailyLimitMinutes = minutes; statusMessage = "上限を $minutes 分に設定！"
                                    }
                            }

                                    private fun saveParentPin(pin: String) {
                                if (familyDocId.isEmpty()) {
                                    statusMessage = "❌ ペアリングを完了してください。"; return
                                }
                                Firebase.firestore.collection("families").document(familyDocId).update("parentPin", pin)
                                    .addOnSuccessListener { parentPin = pin; statusMessage = "PINを「$pin」に保存！" }
                            }

                                    private fun approveAppRequest(isApproved: Boolean) {
                                if (familyDocId.isEmpty()) return
                                val status = if (isApproved) "approved" else "rejected"
                                Firebase.firestore.collection("families").document(familyDocId)
                                    .update(mapOf("pendingAppRequest" to "", "appApprovalStatus" to status))
                                    .addOnSuccessListener {
                                        pendingAppRequest = ""; statusMessage = if (isApproved) "承認しました" else "却下しました"
                                    }
                            }

                                    private fun requestAppDownload(appName: String) {
                                if (appName.isEmpty() || familyDocId.isEmpty()) return
                                Firebase.firestore.collection("families").document(familyDocId)
                                    .update(mapOf("pendingAppRequest" to appName, "appApprovalStatus" to "pending"))
                                    .addOnSuccessListener { statusMessage = "アプリ「$appName」を申請！" }
                            }

                                    private fun saveWebLimit(url: String) {
                                if (familyDocId.isEmpty()) return
                                Firebase.firestore.collection("families").document(familyDocId).update("blockedUrl", url)
                                    .addOnSuccessListener { blockedUrl = url; statusMessage = "ウェブ制限を保存！" }
                            }

    private fun logoutUser() {
        // 1. Firebaseのサインアウト
        auth.signOut()
    }




    private fun linkEmail(emailInput: String, passInput: String) {
                                val user = auth.currentUser ?: return
                                val credential = EmailAuthProvider.getCredential(emailInput, passInput)
                                user.linkWithCredential(credential).addOnSuccessListener { statusMessage = "📧紐付け成功！" }
                                    .addOnFailureListener { e -> statusMessage = "失敗: ${e.message}" }
                            }

    private fun showBiometricPrompt(onFailure: () -> Unit = {}, onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
                                val biometricPrompt =
                                    BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                            super.onAuthenticationSucceeded(result); onSuccess()
                                        }
                                        override fun onAuthenticationFailed() {
                                            super.onAuthenticationFailed(); statusMessage = "認証失敗"
                                            onFailure()
                                        }
                                    })
                                val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("親認証")
                                    .setSubtitle("指紋や顔認証でロック解除")
                                    .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                                    .build()
                                biometricPrompt.authenticate(promptInfo)
                            }

                                    private fun createPairingCode(parentUid: String) {
                                if (parentUid.isEmpty()) return
                                val db = Firebase.firestore
                                val code = (100000..999999).random().toString()
                                val expiresAt = Date(System.currentTimeMillis() + 5 * 60 * 1000)
                                db.collection("pairing_codes").document(code)
                                    .set(mapOf("parentUid" to parentUid, "expiresAt" to Timestamp(expiresAt)))
                                    .addOnSuccessListener { pairingCode = code; statusMessage = "コード発行！5分間有効" }
                            }

                                    private fun verifyPairingCode(enteredCode: String, childUid: String) {
                                if (enteredCode.isEmpty() || childUid.isEmpty()) return
                                val db = Firebase.firestore
                                db.collection("pairing_codes").document(enteredCode).get().addOnSuccessListener { doc ->
                                    val parentUid = doc.getString("parentUid")
                                    val expiresAt = doc.getTimestamp("expiresAt")
                                    if (parentUid != null && expiresAt != null && expiresAt.toDate().after(Date())) {
                                        val familyData = mapOf(
                                            "parentId" to parentUid,
                                            "childId" to childUid,
                                            "pairedAt" to Timestamp.now(),
                                            "isLimited" to false,
                                            "startTime" to "21:00",
                                            "endTime" to "07:00",
                                            "dailyLimitMinutes" to 60,
                                            "allLimitsOff" to false,
                                            "parentPin" to "1234",
                                            "childLat" to 0.0,
                                            "childLng" to 0.0,
                                            "pendingAppRequest" to "",
                                            "blockedUrl" to "",
                                            "appApprovalStatus" to ""
                                        )
                                        db.collection("families").document("${parentUid}_$childUid").set(familyData)
                                            .addOnSuccessListener {
                                                familyDocId = "${parentUid}_$childUid"
                                                selectedRole = "child"
                                                saveLocalRole("child")
                                                startListeningToLimit(familyDocId)
                                                statusMessage = "🎉親子ペアリング成功！"
                                                db.collection("pairing_codes").document(enteredCode).delete()
                                            }
                                    } else {
                                        statusMessage = "❌コードが無効または期限切れ"
                                    }
                                }
                            }

                                    private fun loadLocalUsage() {
                                val prefs = getSharedPreferences("dopax_prefs", MODE_PRIVATE)
                                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(Date())
                                usedSecondsToday = if (prefs.getString("usage_date", "") == today) prefs.getInt("used_seconds", 0) else 0
                            }

                                    private fun saveLocalUsage(seconds: Int) {
                                val prefs = getSharedPreferences("dopax_prefs", MODE_PRIVATE)
                                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(Date())
                                prefs.edit().putString("usage_date", today).putInt("used_seconds", seconds).apply()
                            }

                                    private fun saveLocalRole(role: String) = getSharedPreferences("dopax_prefs", MODE_PRIVATE).edit().putString("selected_role", role).apply()

                                    private fun loadLocalRole(): String = getSharedPreferences("dopax_prefs", MODE_PRIVATE).getString("selected_role", "") ?: ""

                            private fun isCurrentTimeInRestrictedRange(start: String, end: String): Boolean {
                                return try {
                                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    val now = sdf.parse(sdf.format(Date())) ?: return false
                                    val startVal = sdf.parse(start) ?: return false
                                    val endVal = sdf.parse(end) ?: return false
                                    if (startVal.after(endVal)) now.after(startVal) || now.before(endVal) else now.after(startVal) && now.before(endVal)
                                } catch (e: Exception) { false }
                            }

    @Composable
    fun ModeSelectionScreen(onModeSelected: (String) -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒 子ども端末のロックモードを選択", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // 🛡️ 選択肢1：鉄壁制限モード
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().clickable { onModeSelected("device_owner") } // 🟢 修正済み
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🛡️ 鉄壁制限モード（パソコン/初期化が必要）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("・子どもによるアプリ削除をOSレベルで100%防止\n・ホームボタンや履歴ボタンを完全非表示化\n・一時停止（アプリ一覧のグレーアウト）に対応", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ⚙️ 選択肢2：かんたん制限モード
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().clickable { onModeSelected("accessibility") } // 🟢 修正済み
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ かんたん制限モード（パソコン不要/数秒で完了）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("・設定の「ユーザー補助」をONにするだけで簡単起動\n・アプリ起動時に自動で瞬時にロック画面を表示", style = MaterialTheme.typography.bodySmall)
                }
            }

        }
    }

    // 🟢 追加：ドキュメントIDをローカルに保存
    private fun saveLocalFamilyDocId(docId: String) {
        getSharedPreferences("dopax_prefs", MODE_PRIVATE).edit().putString("family_doc_id", docId).apply()
    }

    // 🟢 追加：ドキュメントIDをローカルから読込
    private fun loadLocalFamilyDocId(): String {
        return getSharedPreferences("dopax_prefs", MODE_PRIVATE).getString("family_doc_id", "") ?: ""
    }

}
