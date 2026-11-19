package kr.co.akpda.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.co.akpda.shared.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

@Serializable
data class Pending(val owner: String, val endpoint: String, val request: WorkRequest)
data class UiState(
    val login: LoginResponse? = null, val screen: String = "", val busy: Boolean = false,
    val fields: Map<String, String> = mapOf("MODE" to "R", "RESOURCE_GB" to "T"),
    val options: List<Row> = emptyList(), val orders: List<Row> = emptyList(),
    val first: Row = emptyMap(), val second: Row = emptyMap(), val boxes: List<Row> = emptyList(),
    val message: String = "", val pending: Pending? = null,
    val ocrCandidates: List<String> = emptyList(), val ocrText: String = ""
)

class ApiException(val status: Int, message: String) : Exception(message)
class PdaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("akpda", 0)
    private val json = Json { ignoreUnknownKeys = true }
    var endpoint by mutableStateOf(
        prefs.getString(
            "endpoint",
            "http://10.0.2.2:8080"
        )!!
    )
        private set
    var plant by mutableStateOf(prefs.getString("plant", "01")!!)
        private set
    var userId by mutableStateOf(prefs.getString("user", "demo")!!)
        private set
    var ui by mutableStateOf(UiState(pending = runCatching {
        json.decodeFromString<Pending>(
            prefs.getString(
                "pending",
                ""
            )!!
        )
    }.getOrNull()))
        private set
    private var owner = ""
    fun message(text: String) {
        ui = ui.copy(message = text)
    }

    fun settings(url: String, code: String) {
        runCatching {
            val uri = URI(url.trim().trimEnd('/'))
            require(
                uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty()
                    .isEmpty()
            ) { "서버 주소는 https://서버:포트 형식으로 입력하세요." }
            require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) { "배포 앱에는 HTTPS 주소가 필요합니다." }
            require(code.matches(Regex("[A-Za-z0-9_.-]{1,30}"))) { "공장 코드를 확인하세요." }
            require(!ui.busy) { "처리 중입니다." }
            val old = ui.login?.token
            if (old != null) viewModelScope.launch {
                runCatching {
                    post(
                        "logout",
                        "{}",
                        old,
                        endpoint
                    )
                }
            }
            endpoint = uri.toString(); plant = code
            prefs.edit().putString("endpoint", endpoint).putString("plant", plant).apply()
            ui = UiState(pending = ui.pending, message = "설정을 저장했습니다. 다시 로그인하세요.")
        }.onFailure { message(it.message ?: "설정 오류") }
    }

    fun login(id: String, password: String) = runTask {
        require(id.isNotBlank() && password.isNotEmpty()) { "사번과 비밀번호를 입력하세요." }
        val result = post(
            "login",
            json.encodeToString(LoginRequest.serializer(), LoginRequest(plant, id, password))
        )
        val session = json.decodeFromString<LoginResponse>(result)
        userId = id; owner = plant + ":" + id
        prefs.edit().putString("user", id).apply()
        ui = UiState(
            login = session,
            pending = ui.pending,
            message = if (session.demo) "데모 모드 · 실제 DB에 저장하지 않습니다." else "로그인했습니다."
        )
    }

    fun logout() {
        if (ui.busy) return
        val token = ui.login?.token
        if (token != null) viewModelScope.launch { runCatching { post("logout", "{}", token) } }
        ui = UiState(pending = ui.pending); owner = ""
    }

    fun open(screen: String) {
        if (ui.busy) return
        ui = ui.copy(
            screen = screen,
            fields = mapOf(
                "MODE" to "R",
                "RESOURCE_GB" to "T",
                "ORDER_DT" to java.time.LocalDate.now().toString()
            ),
            options = emptyList(),
            orders = emptyList(),
            first = emptyMap(),
            second = emptyMap(),
            boxes = emptyList(),
            ocrCandidates = emptyList(), ocrText = "",
            message = ""
        )
        if (screen in setOf("PDA2002M1", "PDA2003M1", "PDA2004M1", "PDA2005M1")) request("options")
    }

    fun edit(key: String, value: String) {
        if (ui.busy) return
        val fields = ui.fields + (key to value)
        ui = when (key) {
            "QTY" -> ui.copy(fields = fields)
            "SCAN2" -> ui.copy(fields = fields, second = emptyMap())
            "SCAN" -> ui.copy(fields = fields, first = emptyMap())
            "PRODT_ORDER_NO" -> ui.copy(fields = fields, first = emptyMap())
            "ORDER_DT", "WC_CD" -> ui.copy(
                fields = fields - "PRODT_ORDER_NO",
                orders = emptyList(),
                first = emptyMap()
            )

            else -> ui.copy(
                fields = fields,
                first = emptyMap(),
                second = emptyMap(),
                boxes = emptyList()
            )
        }
        if (key in setOf("MODE", "RESOURCE_GB")) request("options")
    }

    fun removeBox(id: String) {
        if (!ui.busy) ui = ui.copy(boxes = ui.boxes.filterNot { it["BX_ID"] == id })
    }

    fun injectionPhoto(photo: String, capturedAt: String, text: String) {
        if (ui.busy || ui.screen != Injection.SCREEN) return
        ui = ui.copy(fields = mapOf("MACHINE" to ui.fields["MACHINE"].orEmpty(),
            "MODEL" to ui.fields["MODEL"].orEmpty().ifBlank { "SPE-250" },
            "PHOTO" to photo, "CAPTURED_AT" to capturedAt),
            ocrCandidates = Regex("(?<![A-Za-z0-9.])[0-9]{1,9}(?:\\.[0-9]{1,4})?(?![A-Za-z0-9.])")
                .findAll(text).map { it.value }.distinct().toList(), ocrText = text,
            message = "사진을 읽었습니다. 항목별 숫자를 선택하거나 직접 입력하고 확인하세요.")
    }

    fun saveInjection() = runTask {
        require(ui.pending == null) { "미확정 저장 요청부터 재확인하세요." }
        val fields = ui.fields.filterKeys { it in Injection.labels || it in setOf("MACHINE", "MODEL", "PHOTO", "CAPTURED_AT", "CONFIRMED", "ROBOT") }
        Injection.validate(fields)
        val p = Pending(owner, endpoint, WorkRequest(Injection.SCREEN, "save", fields, UUID.randomUUID().toString()))
        persist(p); executeWrite(p)
    }

    fun request(operation: String) = runTask {
        val snapshot = ui
        if (operation in setOf("save", "cancel")) {
            require(ui.pending == null) { "미확정 저장 요청부터 재확인하세요." }
            require(snapshot.first.isNotEmpty() || snapshot.boxes.isNotEmpty()) { "대상을 먼저 조회하세요." }
            if (snapshot.screen in setOf(
                    "PDA2002M1",
                    "PDA2003M1",
                    "PDA2004M1"
                ) && operation == "save"
            ) positive(snapshot.fields["QTY"].orEmpty(), "처리량")
            if (snapshot.screen == "PDA2006M1") require(snapshot.second.isNotEmpty()) { "생산 LOT를 조회하세요." }
            if (snapshot.screen == "PDA2005M1") require(!snapshot.fields["PRODT_ORDER_NO"].isNullOrBlank()) { "작업지시를 선택하세요." }
        }
        val fields = snapshot.fields + if (snapshot.screen == "PDA2001M1") mapOf(
            "BOXES" to snapshot.boxes.joinToString(",") { it.getValue("BX_ID") }) else emptyMap()
        val r = WorkRequest(
            snapshot.screen,
            operation,
            fields,
            if (operation in setOf("save", "cancel")) UUID.randomUUID().toString() else ""
        )
        if (operation in setOf("save", "cancel")) {
            val p = Pending(owner, endpoint, r); persist(p); executeWrite(p)
        } else {
            val response = json.decodeFromString<WorkResponse>(
                post(
                    "work",
                    json.encodeToString(WorkRequest.serializer(), r),
                    snapshot.login?.token
                )
            )
            when (operation) {
                "options" -> {
                    val key = when (snapshot.screen) {
                        "PDA2002M1" -> "RESOURCE_CD"; "PDA2005M1" -> "WC_CD"; else -> "CHILD_ITEM_CD"
                    }
                    ui = ui.copy(
                        options = response.rows,
                        fields = ui.fields + (key to response.rows.firstOrNull()?.get("CODE")
                            .orEmpty())
                    )
                }

                "orders" -> ui = ui.copy(orders = response.rows)
                "secondary" -> ui = ui.copy(second = response.rows.firstOrNull().orEmpty())
                "search" -> {
                    val row = response.rows.firstOrNull() ?: error("조회 결과가 없습니다.")
                    if (snapshot.screen == "PDA2001M1") {
                        val boxes = snapshot.boxes + row; validateBoxes(boxes)
                        ui =
                            ui.copy(boxes = boxes, first = row, fields = ui.fields + ("SCAN" to ""))
                    } else ui = ui.copy(
                        first = row,
                        fields = ui.fields + if (snapshot.screen == "PDA2002M1") mapOf("QTY" to row["QTY"].orEmpty()) else emptyMap()
                    )
                }
            }
        }
    }

    fun retryPending() = runTask {
        val p = ui.pending ?: error("미확정 요청이 없습니다.")
        require(p.owner == owner && p.endpoint == endpoint) { "최초 저장 요청과 같은 서버·공장·사번으로 로그인하세요." }
        executeWrite(p)
    }

    private suspend fun executeWrite(p: Pending) {
        try {
            val response = json.decodeFromString<WorkResponse>(
                post(
                    "work",
                    json.encodeToString(WorkRequest.serializer(), p.request),
                    ui.login?.token,
                    p.endpoint
                )
            )
            persist(null)
            ui = ui.copy(
                first = emptyMap(),
                second = emptyMap(),
                boxes = emptyList(),
                fields = ui.fields - setOf("SCAN", "SCAN2", "QTY"),
                message = response.message
            )
            if (p.request.screen == Injection.SCREEN && ui.screen == Injection.SCREEN) {
                ui = ui.copy(fields = mapOf("MACHINE" to ui.fields["MACHINE"].orEmpty(),
                    "MODEL" to ui.fields["MODEL"].orEmpty()), ocrText = "", ocrCandidates = emptyList())
            }
        } catch (e: ApiException) {
            if (e.status in 400..499 && e.status != 401) persist(null)
            throw e
        }
    }

    private var lastNotice = ""
    suspend fun pollNotifications() {
        val token = ui.login?.token ?: return
        if (ui.busy) return
        runCatching {
            val result = json.decodeFromString<WorkResponse>(post("notifications", "{}", token))
            if (result.message.isNotBlank() && result.message != lastNotice) {
                lastNotice = result.message
                if (!ui.busy) ui = ui.copy(message = "알림: " + result.message)
            }
        }
    }

    private fun persist(p: Pending?) {
        check(
            prefs.edit().apply {
                if (p == null) remove("pending") else putString(
                    "pending",
                    json.encodeToString(Pending.serializer(), p)
                )
            }.commit()
        ) { "저장 요청 정보를 보관하지 못했습니다." }
        ui = ui.copy(pending = p)
    }

    private fun runTask(block: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is ApiException && e.status == 401) ui = ui.copy(login = null, screen = "")
                ui = ui.copy(message = e.message ?: "통신 오류. 저장 요청이 있으면 동일 요청을 재확인하세요.")
            } finally {
                ui = ui.copy(busy = false)
            }
        }
    }

    private suspend fun post(
        route: String,
        body: String,
        token: String? = null,
        base: String = endpoint
    ): String = withContext(Dispatchers.IO) {
        val c = URI(base + "/api/" + route).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout =
                45000; c.instanceFollowRedirects = false; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val text =
                (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw ApiException(
                status,
                runCatching { json.decodeFromString<ApiError>(text).message }.getOrDefault("서버 응답 오류: " + status)
            )
            text
        } finally {
            c.disconnect()
        }
    }
}
