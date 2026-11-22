package audio.own.bookplayer.imports

import audio.own.bookplayer.closeMain

import audio.own.book.library.Visibility
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.eventually
import audio.own.bookplayer.mainThread
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Import a Book form: tags fill it, files are put in order, mobile data is asked about. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportBookViewModelTest {
    private val main = mainThread()
    private val dir = File(RuntimeEnvironment.getApplication().filesDir, "imports-form-${System.nanoTime()}")
    private val art = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9)

    private val reader = object : ImportReader {
        val released = mutableListOf<String>()
        override suspend fun documents(uris: List<String>, wantArtwork: Boolean) = uris.mapIndexed { i, uri ->
            PickedFile(uri, "0${i + 1} Chapter.mp3", "Chapter ${i + 1}", "Jane Austen", "Emma", 60_000, 40L shl 20, "audio/mpeg", uri, if (wantArtwork && i == 0) art else null)
        }
        override suspend fun folder(tree: String, wantArtwork: Boolean) = listOf(
            PickedFile("$tree/a", "01 Chapter.mp3", null, null, null, 1_000, 1, "audio/mpeg", tree),
            PickedFile("$tree/b", "03 Chapter.mp3", null, null, null, 2_000, 2, "audio/mpeg", tree),
        )
        override suspend fun picture(uri: String) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        override fun release(grants: Collection<String>) {
            released += grants
        }
    }
    private val scheduler = object : ImportScheduler {
        val queued = mutableListOf<String>()
        override fun enqueue(importId: String, replace: Boolean) {
            queued += importId
        }
        override fun cancel(importId: String) = Unit
    }
    private val imports = BookImports(dir, { null }, object : ImportFileAccess {
        override fun open(uri: String): InputStream? = null
        override fun release(grant: String) = Unit
    }, scheduler, AppLog(null, echo = false))
    private var metered = false

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeMain(main)
        dir.deleteRecursively()
    }

    private fun form() = ImportBookViewModel("s1", reader, imports) { metered }

    @Test
    fun `the first file's tags fill the form, and the same file twice is one`() = runBlocking {
        val vm = form()
        vm.addDocuments(listOf("content://d/1", "content://d/2"))
        eventually { vm.state.value.files.size == 2 && !vm.state.value.reading }
        val s = vm.state.value
        assertEquals("Emma", s.title)
        assertEquals("Jane Austen", s.author)
        assertArrayEquals(art, s.cover)
        assertEquals(120_000L, s.totalMs)
        assertEquals(80L shl 20, s.totalBytes)
        assertTrue("artwork isn't kept per file", s.files.all { it.artwork == null })

        vm.setTitle("Emma, read again")
        vm.addDocuments(listOf("content://d/9"))
        eventually { !vm.state.value.reading }
        assertEquals("a file of a name already there is left out", 2, vm.state.value.files.size)
        assertEquals("what was typed stays", "Emma, read again", vm.state.value.title)
    }

    @Test
    fun `a folder's files come in, and can be reordered and removed`() = runBlocking {
        val vm = form()
        vm.addFolder("content://tree/t")
        eventually { vm.state.value.files.size == 2 }
        assertEquals(listOf("01 Chapter.mp3", "03 Chapter.mp3"), vm.state.value.files.map { it.fileName })
        vm.move(1, -1)
        assertEquals(listOf("03 Chapter.mp3", "01 Chapter.mp3"), vm.state.value.files.map { it.fileName })
        vm.move(0, -1)
        assertEquals("nothing moves past the top", "03 Chapter.mp3", vm.state.value.files.first().fileName)
        vm.remove(0)
        assertEquals(listOf("01 Chapter.mp3"), vm.state.value.files.map { it.fileName })
        vm.setCover("content://media/1")
        eventually { vm.state.value.cover != null }
    }

    @Test
    fun `over mobile data past 100 MB the listener is asked first`() = runBlocking {
        metered = true
        val vm = form()
        vm.addDocuments(listOf("content://d/1", "content://d/2", "content://d/3"))
        eventually { vm.state.value.files.size == 3 }
        vm.addToLibrary()
        assertEquals(120L shl 20, vm.state.value.askMobile)
        assertTrue(scheduler.queued.isEmpty())
        vm.dismissMobile()
        assertNull(vm.state.value.askMobile)

        vm.remove(2)
        vm.remove(1)
        vm.addToLibrary()
        eventually { vm.state.value.done }
        assertNull("40 MB goes without asking", vm.state.value.askMobile)
        assertEquals(1, scheduler.queued.size)
    }

    @Test
    fun `Add to Library hands the import over with the names split`() = runBlocking {
        val vm = form()
        vm.addDocuments(listOf("content://d/1"))
        eventually { vm.state.value.files.size == 1 }
        vm.setAuthor("Jane Austen; Anne Bell")
        vm.setNarrator(" Kim ")
        vm.setVisibility(Visibility.FAMILY)
        vm.addToLibrary()
        eventually { vm.state.value.done }
        val record = imports.state.value.records.values.single()
        assertEquals("Emma", record.title)
        assertEquals(listOf("Jane Austen", "Anne Bell"), record.authors)
        assertEquals(listOf("Kim"), record.narrators)
        assertEquals("family", record.visibility)
        assertEquals("image/jpeg", record.cover!!.contentType)
        assertEquals(listOf("content://d/1"), record.grants)
        assertEquals(listOf(record.id), scheduler.queued)
    }
}
