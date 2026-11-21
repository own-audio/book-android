package audio.own.book.library

import audio.own.book.library.catalogue.BookCatalogueDatabase
import audio.own.book.library.catalogue.OwnAudioCatalogue
import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.audiobookshelf.DeviceInfo
import audio.own.kit.audiobookshelf.RateLimitPolicy
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import androidx.room.Room
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import audio.own.kit.audiobookshelf.TokenSession as AbsTokenSession
import audio.own.kit.audiobookshelf.Tokens as AbsTokens

/**
 * A MockWebServer answering through [handler], keeping every request in order. An assertion that
 * fails inside the handler answers 500 and is thrown again when the stub is closed, so it is
 * reported instead of surfacing as a timeout.
 */
class Stub(private val handler: (RecordedRequest) -> MockResponse) : Closeable {
    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val failures = CopyOnWriteArrayList<Throwable>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return try {
                    handler(request)
                } catch (e: Throwable) {
                    failures.add(e)
                    MockResponse.Builder().code(500).body("stub failed").build()
                }
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    override fun close() {
        server.close()
        failures.firstOrNull()?.let { throw it }
    }
}

val http = OkHttpClient()

fun json(text: String, code: Int = 200, type: String = "application/json"): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

fun text(text: String, code: Int = 200): MockResponse = MockResponse.Builder().code(code).addHeader("Content-Type", "text/plain").body(text).build()

fun problem(code: String, status: Int): MockResponse =
    json("""{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"}""", status, "application/problem+json")

/** A snapshot body: one line per element; without [end] it is cut short. */
fun ndjson(begin: String?, records: List<Triple<String, String, String>>, end: String?): MockResponse {
    val lines = buildList {
        begin?.let { add("""{"type":"begin","cursor":"$it"}""") }
        records.forEach { (kind, id, record) -> add("""{"type":"record","kind":"$kind","id":"$id","record":$record}""") }
        end?.let { add("""{"type":"end","cursor":"$it"}""") }
    }
    return json(lines.joinToString("\n", postfix = "\n"), type = "application/x-ndjson")
}

fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body?.utf8()?.ifEmpty { "{}" } ?: "{}").jsonObject

fun ownAudioClient(stub: Stub, isOffline: () -> Boolean = { false }) = OwnAudioClient(
    baseUrl = stub.url,
    http = http,
    userAgent = "own.audio Books/0.0 (Android test; JVM)",
    tokens = TokenSession(Tokens("a", "r", Instant.now().plusSeconds(3600), "")),
    isOffline = isOffline,
)

/** A catalogue in memory; Robolectric gives Room its Android side. */
fun memoryCatalogue(): OwnAudioCatalogue = OwnAudioCatalogue(
    Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BookCatalogueDatabase::class.java).build(),
)

val testDevice = DeviceInfo(deviceId = "d1", clientName = "own.audio Books", clientVersion = "0.0.0", manufacturer = "Google", model = "Pixel 8", sdkVersion = "35")

fun absClient(url: String) = AudiobookshelfClient(
    host = url.toHttpUrl(),
    tokens = AbsTokenSession(AbsTokens("t", null, AbsTokens.NEVER)),
    http = AudiobookshelfHttp(http, "own.audio Books/0.0 (Android test; JVM)", RateLimitPolicy(sleep = {})),
)

fun absSource(url: String) = AudiobookshelfBookSource("server-2", absClient(url), "lib1", "u1", testDevice)
