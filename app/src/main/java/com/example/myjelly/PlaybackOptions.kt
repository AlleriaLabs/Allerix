package com.example.myjelly

enum class Quality(val label: String, val height: Int, val videoBitrate: Int) {
    ORIGINAL("Original (Direct Play)", 0, 0),
    P480("480p", 480, 1_500_000),
    P720("720p", 720, 4_000_000),
    P1080("1080p", 1080, 8_000_000),
    K2("2K (1440p)", 1440, 16_000_000),
    K4("4K (2160p)", 2160, 35_000_000)
}

enum class AudioMode(val label: String, val channels: Int, val bitrate: Int) {
    ORIGINAL("Original", 0, 0),
    STEREO("Stereo", 2, 192_000),
    MONO("Mono", 1, 96_000),
    SURROUND51("5.1", 6, 384_000),
    SURROUND71("7.1", 8, 512_000)
}
