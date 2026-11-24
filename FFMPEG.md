# FFmpeg in StreamX TV

StreamX TV uses FFmpeg (https://ffmpeg.org) under the LGPL-2.1 license (app/libs/COPYING.LGPLv2.1), through
Media3's FFmpeg decoder extension (Apache-2.0). FFmpeg 6.0 was configured without --enable-gpl and with audio
decoders only (ac3, eac3, dca, truehd, mlp, mp3, aac, vorbis, opus, flac, alac); it is a separate shared library
(libffmpegJNI.so). How it is built: .github/workflows/ffmpeg-tv.yml (source: github.com/FFmpeg/FFmpeg release/6.0).
