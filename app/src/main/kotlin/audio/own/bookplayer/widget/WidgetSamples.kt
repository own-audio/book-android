package audio.own.bookplayer.widget

import androidx.glance.ImageProvider
import audio.own.bookplayer.R

/** Made-up books for the widget picker's previews (Android 15 and later draws them from here). */
object WidgetSamples {
    private val books = listOf(
        Triple("The Lighthouse Keeper", "Mira Lane", 0.42),
        Triple("Paper Boats", "Ada Kovač", 0.18),
        Triple("A Winter in the Hills", "Juno Park", 0.73),
        Triple("Small Hours", "Tomas Brandt", 0.05),
        Triple("The Orchard", "Lena Hart", 0.56),
        Triple("Northern Lights", "Sam Okafor", 0.31),
        Triple("Salt and Stone", "Iris Bell", 0.88),
        Triple("Bedtime Stories", "Family Shelf", 0.64),
    )

    private val pictures = listOf(
        R.drawable.widget_sample_cover_1, R.drawable.widget_sample_cover_2, R.drawable.widget_sample_cover_3, R.drawable.widget_sample_cover_4,
        R.drawable.widget_sample_cover_5, R.drawable.widget_sample_cover_6, R.drawable.widget_sample_cover_7, R.drawable.widget_sample_cover_8,
    )

    fun state(): WidgetState {
        val shelf = books.mapIndexed { index, (title, author, fraction) ->
            WidgetSnapshot.Entry("sample|$index", title, author, fraction, remainingMs = ((1 - fraction) * 9 * 3_600_000).toLong(), cover = "sample$index")
        }
        return WidgetState(
            player = WidgetPlayer(
                title = "The Lighthouse Keeper", author = "Mira Lane", cover = "sample0", isPlaying = true,
                positionMs = 13_608_000, durationMs = 32_400_000, remainingMs = 18_792_000,
            ),
            shelf = shelf,
            covers = pictures.mapIndexed { index, res -> "sample$index" to ImageProvider(res) }.toMap(),
        )
    }
}
