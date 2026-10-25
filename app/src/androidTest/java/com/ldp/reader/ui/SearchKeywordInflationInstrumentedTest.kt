package com.ldp.reader.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemKeywordBinding
import com.ldp.reader.ui.activity.SearchActivity
import com.ldp.reader.ui.adapter.KeyWordAdapter
import com.ldp.reader.ui.theme.InkThemes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchKeywordInflationInstrumentedTest {
    @Test
    fun keywordSuggestionsInflateInSearchActivity() {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertSuggestionBinds(activity) }
        }
    }

    @Test
    fun keywordSuggestionsInflateInEveryLightAndDarkTheme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration)
                configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                val configured = instrumentation.targetContext.createConfigurationContext(configuration)
                for (inkTheme in InkThemes.all) {
                    val context = ContextThemeWrapper(configured, R.style.AppTheme)
                    context.theme.applyStyle(inkTheme.overlay, true)
                    assertSuggestionBinds(context)
                }
            }
        }
    }

    private fun assertSuggestionBinds(context: Context) {
        val adapter = KeyWordAdapter()
        adapter.refreshItems(listOf("剑来"))
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.createViewHolder(parent, 0)
        adapter.bindViewHolder(holder, 0)
        val label = ItemKeywordBinding.bind(holder.itemView).keywordTvName
        assertEquals("剑来", label.text.toString())
        assertEquals(ContextCompat.getColor(context, R.color.ink_text_primary), label.currentTextColor)

        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        holder.itemView.isPressed = true
        holder.itemView.background.setBounds(0, 0, 8, 8)
        holder.itemView.background.draw(Canvas(bitmap))
        assertEquals(ContextCompat.getColor(context, R.color.ink_surface_pressed), bitmap.getPixel(4, 4))
        bitmap.recycle()
    }
}
