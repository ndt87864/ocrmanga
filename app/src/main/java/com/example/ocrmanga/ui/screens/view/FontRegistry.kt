package com.example.ocrmanga.ui.screens.view

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.ocrmanga.R

// Centralized registry of font keys -> FontFamily so multiple UIs can reuse the same list
object FontRegistry {
    val fontOptions: List<Pair<String, FontFamily>> = listOf(
                "mto_comic_1" to FontFamily(Font(R.font.mto_comic_1)),
                "mto_comic_2" to FontFamily(Font(R.font.mto_comic_2)),
                "mto_astro_city" to FontFamily(Font(R.font.mto_astro_city)),
                "mto_augie" to FontFamily(Font(R.font.mto_augie)),
                "mighty_zero" to FontFamily(Font(R.font.mighty_zero)),
                "mto_chancery" to FontFamily(Font(R.font.mto_chancery)),
                "mto_dom" to FontFamily(Font(R.font.mto_dom)),
                "mto_mikes" to FontFamily(Font(R.font.mto_mikes)),
                "mto_sans" to FontFamily(Font(R.font.mto_sans)),
                "mto_shadow" to FontFamily(Font(R.font.mto_shadow)),
                "semhesta" to FontFamily(Font(R.font.semhesta)),
                "kingston" to FontFamily(Font(R.font.kingston)),
                "novitha_script" to FontFamily(Font(R.font.novitha_script)),
                "bougher" to FontFamily(Font(R.font.bougher)),
                "adeline" to FontFamily(Font(R.font.adeline)),
                "blow_brush" to FontFamily(Font(R.font.blow_brush)),
                "boutique_script" to FontFamily(Font(R.font.boutique_script)),
                "break_brush" to FontFamily(Font(R.font.break_brush)),
                "calligraphy" to FontFamily(Font(R.font.calligraphy)),
                "cent_comics" to FontFamily(Font(R.font.cent_comics)),
                "chinacat" to FontFamily(Font(R.font.chinacat)),
                "chit_chat" to FontFamily(Font(R.font.chit_chat)),
                "comic_sans" to FontFamily(Font(R.font.comic_sans)),
                "dexsar_brush" to FontFamily(Font(R.font.dexsar_brush)),
                "entrails" to FontFamily(Font(R.font.entrails)),
                "felt" to FontFamily(Font(R.font.felt)),
                "fresh_script" to FontFamily(Font(R.font.fresh_script)),
                "handelson_two" to FontFamily(Font(R.font.handelson_two)),
                "harry_brush" to FontFamily(Font(R.font.harry_brush)),
                "hiro_misake" to FontFamily(Font(R.font.hiro_misake)),
                "iciel_pony" to FontFamily(Font(R.font.iciel_pony)),
                "imaginary_friend" to FontFamily(Font(R.font.imaginary_friend)),
                "kashima_brush" to FontFamily(Font(R.font.kashima_brush)),
                "lnth" to FontFamily(Font(R.font.lnth)),
                "mto_chranko" to FontFamily(Font(R.font.mto_chranko)),
                "okami" to FontFamily(Font(R.font.okami)),
                "redtowns" to FontFamily(Font(R.font.redtowns)),
                "story_brush" to FontFamily(Font(R.font.story_brush)),
                "wrong_hunt" to FontFamily(Font(R.font.wrong_hunt)),
                "you_murdere" to FontFamily(Font(R.font.you_murdere))
    )

    // Friendly display name for a font key
    fun displayNameFor(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
