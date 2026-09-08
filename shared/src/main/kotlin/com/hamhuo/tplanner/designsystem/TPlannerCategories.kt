package com.hamhuo.tplanner.designsystem

import com.hamhuo.tplanner.designsystem.TPlannerLightTokens.Semantic.Category

data class TPlannerCategoryColors(
    val accent: Int,
    val foreground: Int,
    val background: Int,
    val border: Int,
    val solid: Int,
    val onSolid: Int,
)

/** The order is the persisted colorId contract, independent of item type or interaction state. */
object TPlannerCategories {
    private val palette = listOf(
        TPlannerCategoryColors(Category.Id0.Accent, Category.Id0.Foreground, Category.Id0.Background, Category.Id0.Border, Category.Id0.Solid, Category.Id0.OnSolid),
        TPlannerCategoryColors(Category.Id1.Accent, Category.Id1.Foreground, Category.Id1.Background, Category.Id1.Border, Category.Id1.Solid, Category.Id1.OnSolid),
        TPlannerCategoryColors(Category.Id2.Accent, Category.Id2.Foreground, Category.Id2.Background, Category.Id2.Border, Category.Id2.Solid, Category.Id2.OnSolid),
        TPlannerCategoryColors(Category.Id3.Accent, Category.Id3.Foreground, Category.Id3.Background, Category.Id3.Border, Category.Id3.Solid, Category.Id3.OnSolid),
        TPlannerCategoryColors(Category.Id4.Accent, Category.Id4.Foreground, Category.Id4.Background, Category.Id4.Border, Category.Id4.Solid, Category.Id4.OnSolid),
        TPlannerCategoryColors(Category.Id5.Accent, Category.Id5.Foreground, Category.Id5.Background, Category.Id5.Border, Category.Id5.Solid, Category.Id5.OnSolid),
        TPlannerCategoryColors(Category.Id6.Accent, Category.Id6.Foreground, Category.Id6.Background, Category.Id6.Border, Category.Id6.Solid, Category.Id6.OnSolid),
        TPlannerCategoryColors(Category.Id7.Accent, Category.Id7.Foreground, Category.Id7.Background, Category.Id7.Border, Category.Id7.Solid, Category.Id7.OnSolid),
    )

    fun forColorId(colorId: Int): TPlannerCategoryColors = palette.getOrElse(colorId) { palette[0] }
}
