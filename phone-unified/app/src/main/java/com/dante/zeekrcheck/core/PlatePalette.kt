package com.dante.zeekrcheck.core

/** Sampled from the user's supplied colour chart; no claim about official combination availability. */
object PlatePalette {
    data class Swatch(val name: String, val hex: String)
    val backgrounds = listOf(
        Swatch("黑", "#000000"), Swatch("深蓝", "#1B427D"), Swatch("芥黄", "#DBC943"), Swatch("深绿", "#004437"),
        Swatch("亮黄", "#F2F007"), Swatch("紫粉", "#E655CE"), Swatch("深红", "#A32727"), Swatch("红", "#DC1C1D"),
        Swatch("灰", "#B2B3AE"), Swatch("白", "#FFFFFF"), Swatch("金黄", "#F4CD06"), Swatch("天蓝", "#00A1E0"),
        Swatch("绿", "#17A628"), Swatch("绯红", "#EA0029"), Swatch("橙", "#EC7700"), Swatch("玫红", "#E60A95"),
    )
    val characters = listOf(2, 8, 11, 12, 13, 14, 15).map { backgrounds[it] }
}
