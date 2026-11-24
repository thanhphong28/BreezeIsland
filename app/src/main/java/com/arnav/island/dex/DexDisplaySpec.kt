package com.arnav.island.dex

/** Connection transport for launching the on-device Samsung DeX virtual display. */
enum class DexConnectionMode(val labelEn: String, val labelVi: String) {
    AUTO("Auto (Shizuku first)", "Tự động (Ưu tiên Shizuku)"),
    SHIZUKU("Shizuku", "Shizuku"),
    WIRELESS_ADB("Wireless ADB", "ADB không dây"),
}

/** Where the Samsung DeX desktop should be displayed when turned on. */
enum class DexOutputMode(val labelEn: String, val labelVi: String) {
    AUTO_EXTERNAL_OR_PC("Auto (Monitor / PC)", "Tự động (Màn hình ngoài / PC)"),
    EXTERNAL_DISPLAY("External Monitor / TV", "Màn hình ngoài / Máy chiếu"),
    PC_BROWSER("PC Browser / USB", "Máy tính (Trình duyệt / USB)"),
    PHONE_SCREEN("Phone Screen", "Màn hình điện thoại"),
}

data class DexDisplayPreset(
    val spec: String,
    val labelEn: String,
    val labelVi: String,
    val noteEn: String,
    val noteVi: String,
)

object DexDisplaySpec {
    /** Default Full HD 16:9 desktop spec tailored for external monitors, TVs, projectors & PCs. */
    const val DEFAULT_SPEC = "1920x1080/160"

    private val SPEC_PATTERN = Regex("^(\\d{3,4})x(\\d{3,4})/(\\d{2,3})$")
    private val DISPLAY_ID_PATTERN = Regex("New display: .*\\(id=(\\d+)\\)")

    val PRESETS = listOf(
        DexDisplayPreset(
            spec = "1920x1080/160",
            labelEn = "Monitor / Projector 1080p",
            labelVi = "Màn hình / Máy chiếu 1080p",
            noteEn = "1920×1080 · 160 DPI (sharp desktop workspace for monitors, TVs, projectors & PCs)",
            noteVi = "1920×1080 · 160 DPI (chuẩn máy tính, rộng rãi cho màn hình rời, TV, máy chiếu & PC)",
        ),
        DexDisplayPreset(
            spec = "1920x1080/240",
            labelEn = "1080p Large UI",
            labelVi = "1080p Chữ lớn",
            noteEn = "1920×1080 · 240 DPI (larger text & icons for TVs or projectors viewed from afar)",
            noteVi = "1920×1080 · 240 DPI (chữ & icon lớn, dễ nhìn khi chiếu lên TV hoặc máy chiếu xa)",
        ),
        DexDisplayPreset(
            spec = "2560x1440/213",
            labelEn = "2K QHD Monitor",
            labelVi = "Màn hình 2K QHD",
            noteEn = "2560×1440 · 213 DPI (high-res desktop for 2K/4K monitors & laptops)",
            noteVi = "2560×1440 · 213 DPI (siêu nét cho màn hình 2K/4K và laptop)",
        ),
        DexDisplayPreset(
            spec = "2200x1080/240",
            labelEn = "Z Flip 5 Screen",
            labelVi = "Màn hình Z Flip 5",
            noteEn = "2200×1080 · 240 DPI (for viewing DeX directly on the Z Flip 5 phone screen)",
            noteVi = "2200×1080 · 240 DPI (dành riêng khi muốn xem DeX trực tiếp trên Z Flip 5)",
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
