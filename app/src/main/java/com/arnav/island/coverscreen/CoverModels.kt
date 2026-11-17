package com.arnav.island.coverscreen

enum class CoverPageType(
    val labelVi: String,
    val labelEn: String,
    val descVi: String,
    val descEn: String,
) {
    PET_3D(
        labelVi = "Thú cưng 3D",
        labelEn = "3D Interactive Pet",
        descVi = "Nuôi chó 3D: vuốt ve, cho ăn, ném bóng & lắc máy",
        descEn = "Interactive 3D puppy: pet, feed, play ball & shake",
    ),
    MEDIA(
        labelVi = "Trình phát nhạc",
        labelEn = "Music Player",
        descVi = "Bìa đĩa, sóng âm & điều khiển nhạc trực tiếp",
        descEn = "Album art, waveform & live playback controls",
    ),
    APPS(
        labelVi = "Khởi chạy App",
        labelEn = "App Launcher",
        descVi = "Mở nhanh các ứng dụng yêu thích trên màn ngoài",
        descEn = "Quickly launch favorite apps on cover screen",
    ),
    WEATHER(
        labelVi = "Thời tiết",
        labelEn = "Weather",
        descVi = "Nhiệt độ, cảnh báo mưa & dự báo theo giờ",
        descEn = "Temperature, rain alerts & hourly forecast",
    ),
    CLOCK(
        labelVi = "Đồng hồ & Đếm giờ",
        labelEn = "Clock & Timers",
        descVi = "Đồng hồ lớn, báo thức kế tiếp & bộ đếm giờ",
        descEn = "Large clock face, next alarm & active timers",
    ),
    NOTIFICATIONS(
        labelVi = "Thông báo",
        labelEn = "Notifications",
        descVi = "Xem nhanh các thông báo mới nhất",
        descEn = "Quickly view recent notifications",
    ),
}

enum class CoverPetBreed(
    val labelVi: String,
    val labelEn: String,
    val primaryColor: Int,
    val secondaryColor: Int,
    val earColor: Int,
    val collarColor: Int,
) {
    SHIBA(
        labelVi = "Chó Shiba Vàng",
        labelEn = "Golden Shiba",
        primaryColor = 0xFFE59843.toInt(),
        secondaryColor = 0xFFFFF3E0.toInt(),
        earColor = 0xFFC87628.toInt(),
        collarColor = 0xFFE53935.toInt(),
    ),
    CORGI(
        labelVi = "Chó Corgi Cam",
        labelEn = "Welsh Corgi",
        primaryColor = 0xFFDF8236.toInt(),
        secondaryColor = 0xFFFFFFFF.toInt(),
        earColor = 0xFFB85D19.toInt(),
        collarColor = 0xFF00ACC1.toInt(),
    ),
    HUSKY(
        labelVi = "Chó Husky Xám",
        labelEn = "Siberian Husky",
        primaryColor = 0xFF607D8B.toInt(),
        secondaryColor = 0xFFF5F7FA.toInt(),
        earColor = 0xFF37474F.toInt(),
        collarColor = 0xFF2979FF.toInt(),
    ),
    POODLE(
        labelVi = "Chó Poodle Nâu",
        labelEn = "Teddy Poodle",
        primaryColor = 0xFF8D5B4C.toInt(),
        secondaryColor = 0xFFD7CCC8.toInt(),
        earColor = 0xFF5D3A2E.toInt(),
        collarColor = 0xFFFF4081.toInt(),
    ),
    CYBER(
        labelVi = "Chó Robot Neon",
        labelEn = "Cyber Pup 3D",
        primaryColor = 0xFF00E5FF.toInt(),
        secondaryColor = 0xFFE0F7FA.toInt(),
        earColor = 0xFF651FFF.toInt(),
        collarColor = 0xFFFF007F.toInt(),
    ),
}

fun parseCoverPages(csv: String): List<CoverPageType> {
    val parsed = csv.split(',')
        .mapNotNull { token ->
            val trimmed = token.trim()
            if (trimmed.isEmpty()) null else CoverPageType.entries.firstOrNull { it.name == trimmed }
        }
        .distinct()
    return parsed.ifEmpty { listOf(CoverPageType.PET_3D, CoverPageType.MEDIA, CoverPageType.APPS) }
}

fun encodeCoverPages(list: List<CoverPageType>): String =
    list.distinct().ifEmpty { listOf(CoverPageType.PET_3D) }.joinToString(",") { it.name }
