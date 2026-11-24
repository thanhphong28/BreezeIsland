package com.arnav.island.dex

/** Connection transport for launching the on-device Samsung DeX virtual display. */
enum class DexConnectionMode(val labelEn: String, val labelVi: String) {
    AUTO("Auto (Shizuku first)", "Tự động (Ưu tiên Shizuku)"),
    SHIZUKU("Shizuku", "Shizuku"),
    WIRELESS_ADB("Wireless ADB", "ADB không dây"),
}

data class DexDisplayPreset(
    val spec: String,
    val labelEn: String,
    val labelVi: String,
    val noteEn: String,
    val noteVi: String,
)

object DexDisplaySpec {
    /** Default display spec optimized for Galaxy Z Flip 5 wide inner screen in landscape. */
    const val DEFAULT_SPEC = "2200x1080/240"

    private val SPEC_PATTERN = Regex("^(\\d{3,4})x(\\d{3,4})/(\\d{2,3})$")
    private val DISPLAY_ID_PATTERN = Regex("New display: .*\\(id=(\\d+)\\)")

    val PRESETS = listOf(
        DexDisplayPreset(
            spec = "2200x1080/240",
            labelEn = "Z Flip 5 Wide",
            labelVi = "Chuẩn Z Flip 5",
            noteEn = "2200×1080 · 240 DPI (fills Z Flip 5 landscape)",
            noteVi = "2200×1080 · 240 DPI (tràn màn hình ngang Z Flip 5)",
        ),
        DexDisplayPreset(
            spec = "1920x1080/240",
            labelEn = "Full HD 16:9",
            labelVi = "Full HD 16:9",
            noteEn = "1920×1080 · 240 DPI (classic monitor ratio)",
            noteVi = "1920×1080 · 240 DPI (tỷ lệ màn hình máy tính chuẩn)",
        ),
        DexDisplayPreset(
            spec = "1920x1440/240",
            labelEn = "LocalDex 4:3",
            labelVi = "LocalDex 4:3",
            noteEn = "1920×1440 · 240 DPI (tall multitasking space)",
            noteVi = "1920×1440 · 240 DPI (không gian đa nhiệm cao)",
        ),
        DexDisplayPreset(
            spec = "2560x1080/260",
            labelEn = "Ultrawide 21:9",
            labelVi = "Siêu rộng 21:9",
            noteEn = "2560×1080 · 260 DPI (side-by-side desktop apps)",
            noteVi = "2560×1080 · 260 DPI (mở nhiều cửa sổ song song)",
        ),
    )

    data class Parsed(val width: Int, val height: Int, val dpi: Int)

    fun parse(raw: String): Parsed? {
        val m = SPEC_PATTERN.matchEntire(raw.trim()) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        val dpi = m.groupValues[3].toIntOrNull() ?: return null
        if (w !in 640..3840 || h !in 480..3840 || dpi !in 80..640) return null
        return Parsed(w, h, dpi)
    }

    fun isValid(raw: String): Boolean = parse(raw) != null

    fun normalize(raw: String): String = if (isValid(raw)) raw.trim() else DEFAULT_SPEC

    fun parseDisplayId(serverLogChunk: String): Int? =
        DISPLAY_ID_PATTERN.find(serverLogChunk)?.groupValues?.get(1)?.toIntOrNull()
}
