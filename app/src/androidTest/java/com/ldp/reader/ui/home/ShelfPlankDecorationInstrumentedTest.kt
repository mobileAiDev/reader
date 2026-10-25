package com.ldp.reader.ui.home

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ldp.reader.R
import com.ldp.reader.ui.theme.InkThemeTraits
import com.ldp.reader.ui.theme.InkThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfPlankDecorationInstrumentedTest {
    @Test
    fun lightWoodGrainStaysAttachedToThePlankWhenScrolled() {
        assertWoodGrainMovesWithPlank(Configuration.UI_MODE_NIGHT_NO)
    }

    @Test
    fun darkWoodGrainStaysAttachedToThePlankWhenScrolled() {
        assertWoodGrainMovesWithPlank(Configuration.UI_MODE_NIGHT_YES)
    }

    private fun assertWoodGrainMovesWithPlank(nightMode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val configuration = Configuration(instrumentation.targetContext.resources.configuration)
            configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            val context = ContextThemeWrapper(
                instrumentation.targetContext.createConfigurationContext(configuration), R.style.AppTheme
            )
            context.theme.applyStyle(InkThemes.BOOKSHELF.overlay, true)
            val plank = requireNotNull(InkThemeTraits.of(context).shelfPlank)
            val decoration = ShelfPlankDecoration(plank, { false }, SINK, plank.intrinsicHeight)
            val recycler = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, 3)
                adapter = CoversAdapter()
                addItemDecoration(decoration)
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
            fun shelfTop(): Int {
                val row = requireNotNull(recycler.layoutManager!!.findViewByPosition(0)) as ViewGroup
                return row.top + row.getChildAt(0).bottom - SINK
            }
            fun swatch(top: Int): Bitmap {
                val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                decoration.onDraw(Canvas(bitmap), recycler, RecyclerView.State())
                return Bitmap.createBitmap(bitmap, 0, top, WIDTH, plank.intrinsicHeight).also { bitmap.recycle() }
            }

            val originalTop = shelfTop()
            val before = swatch(originalTop)
            recycler.scrollBy(0, SCROLL)
            assertEquals("The same shelf row must actually scroll", originalTop - SCROLL, shelfTop())
            val after = swatch(shelfTop())
            val pixels = IntArray(before.width * before.height)
            before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
            assertTrue("The comparison must contain visible wood texture", pixels.distinct().size > 20)
            assertTrue(
                "Wood grain must stay on the plank, not reveal different parts of a stationary texture",
                before.sameAs(after)
            )
            before.recycle()
            after.recycle()
        }
    }

    private class CoversAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 18

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, COVER_HEIGHT)
                addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, COVER_HEIGHT))
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 700
        const val COVER_HEIGHT = 180
        const val SINK = 6
        const val SCROLL = 37
    }
}
