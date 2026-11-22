package audio.own.bookplayer

import kotlinx.coroutines.test.resetMain

import audio.own.core.servers.InMemoryAddressMemory
import audio.own.core.servers.NetworkState
import audio.own.core.servers.SecretBox
import audio.own.core.servers.ServerStore
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSessions
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.app.MemoryPreferences
import audio.own.bookplayer.servers.ServerDetector
import audio.own.bookplayer.servers.ServersModel
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/**
 * A pretend own.audio server: discovery, sign-in (password, two-factor, Google), the account,
 * household, sessions and statistics, answering as the real one does. Change its fields to make
 * it answer differently.
 */
class FakeOwnAudio : AutoCloseable {
    val server = MockWebServer()
    // Copy-on-write: tests read it while background work (the address check) still adds to it.
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    var identity = "srv-1"
    var revision = 17
    var google = true
    var demo: Pair<String, String>? = null
    var password = "right password"
    var twoFactorFor: String? = null
    var deleteProblem: String? = null
    var meStatus = 200
    var refreshRefused = false
    var role = "admin"
    var extra: (RecordedRequest) -> MockResponse? = { null }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return extra(request) ?: answer(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path == "/.well-known/own-audio" -> json(discovery())
            path == "/api/v2/auth/token" -> token(form(request))
            path == "/api/v2/auth/revoke" -> json("{}")
            path == "/api/v2/auth/register" -> json(ME)
            path == "/api/v2/me" && request.method == "GET" -> if (meStatus == 200) json(ME.replace("\"admin\"", "\"$role\"")) else problem("unauthorized", meStatus)
            path == "/api/v2/me" && request.method == "DELETE" -> deleteProblem?.let { problem(it, if (it == "last_admin") 409 else 403) } ?: MockResponse.Builder().code(204).build()
            path == "/api/v2/me/sessions" && request.method == "GET" -> json(SESSIONS)
            path.startsWith("/api/v2/me/sessions") -> MockResponse.Builder().code(204).build()
            path == "/api/v2/family" -> json(FAMILY.replace("\"role\":\"admin\"", "\"role\":\"$role\""))
            path == "/api/v2/family/members" -> json(MEMBERS)
            path == "/api/v2/family/storage" -> json("""{"total_bytes":3000,"music_bytes":2000,"audiobooks_bytes":1000,"podcasts_bytes":0}""")
            path == "/api/v2/family/billing" -> problem("feature_unavailable", 501)
            path == "/api/v2/family/invites" && request.method == "GET" -> json("[]")
            path == "/api/v2/family/invites" && request.method == "POST" -> json(INVITE)
            path.startsWith("/api/v2/family/invites/") -> MockResponse.Builder().code(204).build()
            path == "/api/v2/family/join" -> json(FAMILY.replace("Home", "Other"))
            path == "/api/v2/me/stats" -> json(STATS)
            path == "/api/v2/family/stats" -> json("""[{"user_id":"u1","listened_ms":600000}]""")
            path == "/api/v2/me/settings" -> json("""{"stats_visible_to_admin":${request.method == "PATCH"}}""")
            path.startsWith("/api/v2/tracks/") -> json(TRACK)
            path.startsWith("/api/v2/books/") -> problem("not_found", 404)
            else -> problem("not_found", 404)
        }
    }

    private fun discovery(): String {
        val providers = if (google) "[\"google\"]" else "[]"
        val clients = if (google) "{\"google\":{\"web\":\"web-client-id\"}}" else "{}"
        val demoPart = demo?.let { ",\"demo\":{\"email\":\"${it.first}\",\"password\":\"${it.second}\"}" } ?: ""
        return """{"id":"$identity","name":"own.audio","edition":"foss","version":"1.0.5",
            "api":[{"version":2,"path":"/api/v2","revision":$revision,"status":"stable"}],
            "auth":{"password":true,"providers":$providers,"grants":["password","refresh_token"],"clients":$clients},
            "modules":{},"addresses":[{"url":"http://192.168.1.10:8080","scope":"lan"}]$demoPart}"""
    }

    private fun token(form: Map<String, String>): MockResponse = when (form["grant_type"]) {
        "password" -> when {
            form["password"] != password -> problem("invalid_grant", 400)
            form["username"] == twoFactorFor -> problem("mfa_required", 403, ",\"mfa_token\":\"mfa-1\",\"mfa_methods\":[\"totp\"]")
            else -> json(TOKENS)
        }
        "urn:own-audio:params:oauth:grant-type:mfa-otp" -> if (form["otp"] == "123456") json(TOKENS) else problem("invalid_grant", 400)
        "urn:ietf:params:oauth:grant-type:jwt-bearer" -> if (form["assertion"] == "good-id-token") json(TOKENS) else problem("identity_conflict", 409)
        "refresh_token" -> if (refreshRefused) problem("invalid_grant", 400) else json(TOKENS)
        else -> problem("unsupported_grant_type", 400)
    }

    override fun close() = server.close()

    companion object {
        const val TOKENS = """{"access_token":"a1","token_type":"Bearer","expires_in":3600,"refresh_token":"r1","scope":""}"""
        const val ME = """{"id":"u1","email":"a@example.com","display_name":"Alex","family":{"id":"f1","role":"admin","can_upload":true}}"""
        const val FAMILY = """{"id":"f1","name":"Home","me":{"user_id":"u1","role":"admin"}}"""
        const val MEMBERS = """[{"user_id":"u2","display_name":"Sam","role":"member"},{"user_id":"u1","display_name":"Alex","role":"admin"},{"user_id":"u3","display_name":"Kim","pending":true}]"""
        const val INVITE = """{"id":"i1","kind":"link","code":"ABCD-1234","expires_at":"2026-12-01T00:00:00Z","join_url":"https://example.com/join/ABCD-1234"}"""
        const val SESSIONS = """[
            {"id":"s1","device_kind":"ios","device_name":"iPhone","signed_in_at":"2026-11-01T10:00:00Z","last_used_at":"2026-11-20T10:00:00Z","expires_at":"2027-01-01T00:00:00Z","current":false},
            {"id":"s2","device_kind":"android","device_name":"Pixel","signed_in_at":"2026-11-02T10:00:00Z","expires_at":"2027-01-01T00:00:00Z","current":true},
            {"id":"s3","device_kind":"web","signed_in_at":"2026-11-03T10:00:00Z","last_used_at":"2026-11-21T09:00:00Z","expires_at":"2027-01-01T00:00:00Z","current":false}]"""
        const val STATS = """{"from":"2026-10-22T00:00:00Z","to":"2026-11-21T00:00:00Z","group":"day","listened_ms":7800000,"plays":12,
            "by_kind":[{"kind":"track","listened_ms":7800000,"plays":12}],
            "buckets":[{"start":"2026-11-20","listened_ms":3600000},{"start":"2026-11-21","listened_ms":4200000}],
            "top":[{"kind":"track","id":"t1","listened_ms":3600000,"plays":5},{"kind":"book","id":"b9","listened_ms":60000,"plays":1}],
            "streak_days":4,"finished_books":0}"""
        const val TRACK = """{"id":"t1","title":"One","credits":[],"genre_ids":[],"duration_ms":1000,"visibility":"family","owner_id":"u1","created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""

        fun json(text: String, code: Int = 200, type: String = "application/json"): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

        fun problem(code: String, status: Int, extra: String = ""): MockResponse =
            json("""{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"$extra}""", status, "application/problem+json")

        fun form(request: RecordedRequest): Map<String, String> = (request.body?.utf8() ?: "").split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}

/** Stands in for the Keystore: the bytes as they are. */
object PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain
    override fun open(sealed: ByteArray) = sealed
}

/** A [ServersModel] wired to temporary files and the given servers. */
class ModelHarness(hosted: String = "http://127.0.0.1:9", demo: String = "http://127.0.0.1:9") : AutoCloseable {
    val dir: File = Files.createTempDirectory("servers").toFile()
    val store = ServerStore(File(dir, "servers.bin"), PlainBox)
    val log = AppLog(null, echo = false)
    val http = OkHttpClient()
    val preferences = MemoryPreferences()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var cleared = 0
    val model = ServersModel(
        store = store,
        sessions = TokenSessions(),
        http = http,
        userAgent = "own.audio Books/1.0 (Android 16; Test)",
        device = OwnAudioClient.Device("Test phone"),
        preferences = preferences,
        detector = ServerDetector(http, "own.audio Books/1.0 (Android 16; Test)", log, timeoutMs = 3_000),
        addressMemory = InMemoryAddressMemory(),
        network = MutableStateFlow(NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi")),
        cache = null,
        clearCache = { cleared++ },
        isOffline = { false },
        acceptLanguage = { "en" },
        log = log,
        scope = scope,
        hostedService = hosted,
        demoServer = demo,
    )

    init {
        runBlocking { model.load() }
    }

    override fun close() {
        dir.deleteRecursively()
    }
}

/** Waits, in real time, until [condition] holds: the servers answer on their own threads. */
suspend fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    withTimeout(timeoutMs) { while (!condition()) delay(10) }
}

/** View models run on Dispatchers.Main; tests give it one plain thread. */
fun mainThread() = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

/**
 * Lets the work already queued on the test's main thread finish, then puts Dispatchers.Main back.
 * Resetting while a view model is still starting up threw on slower machines (CI).
 */
fun closeMain(main: kotlinx.coroutines.ExecutorCoroutineDispatcher) {
    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(main) {} }
    kotlinx.coroutines.Dispatchers.resetMain()
    main.close()
}

/**
 * A pretend Audiobookshelf server: `/status`, `/login`, `/api/libraries`, `/api/authorize` and
 * the refresh, answering as 2.37 does. Change its fields to make it answer differently.
 */
class FakeAudiobookshelf : AutoCloseable {
    val server = MockWebServer()
    // Copy-on-write: tests read it while background work (the address check) still adds to it.
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    var password = "right password"
    var libraries = """[{"id":"lib1","name":"Fiction","mediaType":"book","displayOrder":1},{"id":"pod","name":"Podcasts","mediaType":"podcast","displayOrder":2},{"id":"lib2","name":"Kids","mediaType":"book","displayOrder":3}]"""
    var defaultLibrary: String? = null
    var rateLimited = false
    var refreshRefused = false
    var authorizeStatus = 200
    var icon = true

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            // Audiobookshelf answers unknown paths with its web app.
            path == "/.well-known/own-audio" -> MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html></html>").build()
            path == "/status" -> FakeOwnAudio.json("""{"app":"audiobookshelf","serverVersion":"2.37.1","isInit":true,"authMethods":["local"]}""")
            path == "/login" -> when {
                rateLimited -> MockResponse.Builder().code(429).addHeader("Retry-After", "600").body("Too many requests").build()
                (request.body?.utf8() ?: "").contains("\"$password\"") -> FakeOwnAudio.json(user())
                else -> MockResponse.Builder().code(401).body("Unauthorized").build()
            }
            path == "/api/libraries" -> FakeOwnAudio.json("""{"libraries":$libraries}""")
            path == "/api/authorize" -> if (authorizeStatus == 200) FakeOwnAudio.json(user()) else MockResponse.Builder().code(authorizeStatus).body("Unauthorized").build()
            path == "/auth/refresh" -> if (refreshRefused) MockResponse.Builder().code(401).body("Unauthorized").build() else FakeOwnAudio.json(user())
            path == "/logout" -> MockResponse.Builder().code(200).body("OK").build()
            path == "/icon192.png" && icon -> MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(okio.Buffer().write(PNG)).build()
            else -> MockResponse.Builder().code(404).body("Not found").build()
        }
    }

    private fun user(): String {
        val default = defaultLibrary?.let { "\"$it\"" } ?: "null"
        return """{"user":{"id":"u7","username":"kim","type":"user","accessToken":"abs-a1","refreshToken":"abs-r1","mediaProgress":[],"bookmarks":[]},
            "userDefaultLibraryId":$default,"serverSettings":{"version":"2.37.1"}}"""
    }

    override fun close() = server.close()

    companion object {
        /** A 32×32 PNG, the smallest icon worth showing. */
        val PNG: okio.ByteString by lazy {
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            okio.ByteString.of(*out.toByteArray())
        }
    }
}
