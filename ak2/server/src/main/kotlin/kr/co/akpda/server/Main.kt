package kr.co.akpda.server

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import kr.co.akpda.shared.*
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.sql.SQLException
import kotlin.system.exitProcess
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val json = Json { ignoreUnknownKeys = false }

private data class Session(
    val principal: Principal,
    val expires: Instant,
    @Volatile var notifySeq: String? = null
)

fun main(args: Array<String>) {
    fun env(n: String, fallback: String = "") = System.getenv(n) ?: fallback
    val mode = env("AKPDA_MODE", "demo")
    require(mode in setOf("demo", "sql")) { "AKPDA_MODE는 demo 또는 sql입니다." }
    if (args.isNotEmpty()) {
        require(args.contentEquals(arrayOf("--check-db"))) { "지원하지 않는 실행 인자입니다." }
        require(mode == "sql") { "DB 연결 검사는 AKPDA_MODE=sql 설정이 필요합니다." }
        require(env("AKPDA_JDBC_URL").isNotBlank() && env("AKPDA_DB_USER").isNotBlank()) { "SQL 접속 환경변수가 필요합니다." }
        try {
            checkSqlConnection(env("AKPDA_JDBC_URL"), env("AKPDA_DB_USER"), env("AKPDA_DB_PASSWORD"))
            println("AKPDA DB connection check: OK (SELECT 1)")
        } catch (e: SQLException) {
            SqlDiagnostics.details(e, listOf(env("AKPDA_DB_PASSWORD"))).forEach { System.err.println(it) }
            exitProcess(1)
        }
        return
    }
    val store: Store = if (mode == "demo") DemoStore() else {
        val contracts = env("AKPDA_WRITE_CONTRACTS").takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString<Map<String, WriteContract>>(Files.readString(Path.of(it))) }
            ?: emptyMap()
        require(env("AKPDA_JDBC_URL").isNotBlank() && env("AKPDA_DB_USER").isNotBlank()) { "SQL 접속 환경변수가 필요합니다." }
        JdbcStore(env("AKPDA_JDBC_URL"), env("AKPDA_DB_USER"), env("AKPDA_DB_PASSWORD"), contracts)
    }
    val service = WorkService(store, env("AKPDA_RECYCLE_CANCEL_PROCEDURE"))
    val sessions = ConcurrentHashMap<String, Session>()
    val attempts = ConcurrentHashMap<String, MutableList<Long>>()
    val server = HttpServer.create(
        InetSocketAddress(
            env("AKPDA_BIND", "127.0.0.1"),
            env("AKPDA_PORT", "8080").toInt()
        ), 64
    )
    server.executor = Executors.newFixedThreadPool(8)
    server.createContext("/") { x ->
        try {
            val route = x.requestURI.path
            if (route == "/health" && x.requestMethod == "GET") {
                x.reply(200, "{\"status\":\"ok\",\"mode\":\"" + mode + "\"}")
            } else {
                require(x.requestMethod == "POST") { "POST 요청만 허용합니다." }
                val limit = if (route == "/api/work") 1_500_000 else 65_536
                val bytes = x.requestBody.readNBytes(limit + 1)
                require(bytes.size <= limit) { "요청 본문 크기 초과" }
                val body = bytes.toString(Charsets.UTF_8)
                when (route) {
                    "/api/login" -> {
                        val ip = x.remoteAddress.address.hostAddress
                        val now = System.currentTimeMillis()
                        val history = attempts.computeIfAbsent(ip) { mutableListOf() }
                        synchronized(history) {
                            history.removeAll { it < now - 60000 }; require(
                            history.size < 10
                        ) { "잠시 후 다시 로그인하세요." }; history += now
                        }
                        sessions.entries.removeIf { it.value.expires.isBefore(Instant.now()) }
                        require(sessions.size < 10000) { "접속 수 초과" }
                        val p = service.login(json.decodeFromString<LoginRequest>(body))
                        val token = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                        sessions[token] = Session(p, Instant.now().plusSeconds(8 * 3600))
                        x.reply(
                            200,
                            json.encodeToString(
                                LoginResponse.serializer(),
                                LoginResponse(token, p.name, p.menus.toList(), store.demo)
                            )
                        )
                    }

                    "/api/work", "/api/logout", "/api/notifications" -> {
                        val token =
                            x.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                                .orEmpty()
                        val s = sessions[token]
                        if (s == null || s.expires.isBefore(Instant.now())) {
                            sessions.remove(token); x.reply(
                                401,
                                json.encodeToString(ApiError.serializer(), ApiError("다시 로그인하세요."))
                            )
                        } else if (route == "/api/logout") {
                            sessions.remove(token); x.reply(200, "{}")
                        } else if (route == "/api/notifications") {
                            val seq = s.notifySeq
                            val notices = if (seq == null) {
                                s.notifySeq = store.call(
                                    "USP_PFW_NOTIFY_SEQ_S1",
                                    mapOf("@NOTIFY_TARGET" to "PDA")
                                ).firstOrNull()?.get("NOTIFY_SEQ") ?: "0"
                                emptyList()
                            } else store.call(
                                "USP_PFW_NOTIFY_PDA_S1",
                                mapOf(
                                    "@NOTIFY_TARGET" to "PDA",
                                    "@NOTIFY_SEQ" to seq,
                                    "@USER_ID" to s.principal.userId
                                )
                            )
                            notices.lastOrNull()?.get("NOTIFY_SEQ")?.let { s.notifySeq = it }
                            x.reply(
                                200,
                                json.encodeToString(
                                    WorkResponse.serializer(),
                                    WorkResponse(
                                        message = notices.firstOrNull()?.get("NOTIFY_MESSAGE")
                                            .orEmpty()
                                    )
                                )
                            )
                        } else x.reply(
                            200,
                            json.encodeToString(
                                WorkResponse.serializer(),
                                service.work(s.principal, json.decodeFromString<WorkRequest>(body))
                            )
                        )
                    }

                    else -> x.reply(
                        404,
                        json.encodeToString(ApiError.serializer(), ApiError("경로가 없습니다."))
                    )
                }
            }
        } catch (e: SQLException) {
            System.err.println("AKPDA SQL request failed: " + x.requestURI.rawPath.take(160))
            SqlDiagnostics.details(e, listOf(env("AKPDA_DB_PASSWORD"))).forEach { System.err.println(it) }
            x.reply(500, json.encodeToString(ApiError.serializer(), ApiError(SqlDiagnostics.clientMessage(e))))
        } catch (e: IllegalArgumentException) {
            x.reply(
                400,
                json.encodeToString(ApiError.serializer(), ApiError(e.message ?: "입력값을 확인하세요."))
            )
        } catch (e: Exception) {
            System.err.println("AKPDA request failed: " + e.javaClass.simpleName)
            x.reply(
                500,
                json.encodeToString(
                    ApiError.serializer(),
                    ApiError("처리하지 못했습니다. 저장 요청이면 동일 요청 재확인 기능을 사용하거나 관리자에게 처리 여부를 확인하세요.")
                )
            )
        } finally {
            x.close()
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(1) })
    server.start()
    println("AKPDA " + mode + " API started. SQL write contracts required in sql mode.")
}

private fun HttpExchange.reply(status: Int, data: String) {
    val bytes = data.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.write(bytes)
}
