package com.ldp.reader.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackSignatureTest {
    @Test
    fun repeatedAudioUrlAcrossChaptersDetectsSameUrlOnDifferentRoutes() {
        assertTrue(
            MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                listOf(
                    "media-chapter:1" to "https://music.163.com/song/media/outer/url?id=1817544979",
                    "media-chapter:2" to "https://music.163.com/song/media/outer/url?id=1817544979"
                )
            )
        )
    }

    @Test
    fun repeatedAudioUrlAcrossChaptersDetectsOneDuplicatePairInMixedSamples() {
        assertTrue(
            MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                listOf(
                    "media-chapter:1" to "https://cdn.example/book/1.m4a",
                    "media-chapter:2" to "https://cdn.example/book/2.m4a",
                    "media-chapter:3" to "https://cdn.example/book/2.m4a"
                )
            )
        )
    }

    @Test
    fun repeatedAudioUrlAcrossChaptersAllowsDistinctChapterUrls() {
        assertFalse(
            MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                listOf(
                    "media-chapter:1" to "https://cdn.example/book/1.m4a",
                    "media-chapter:2" to "https://cdn.example/book/2.m4a"
                )
            )
        )
    }

    @Test
    fun repeatedAudioUrlAcrossChaptersIgnoresRepeatedReadsOfOneRoute() {
        assertFalse(
            MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                listOf(
                    "media-chapter:1" to "https://cdn.example/book/1.m4a",
                    "media-chapter:1" to "https://cdn.example/book/1.m4a"
                )
            )
        )
    }

    @Test
    fun repeatedAudioUrlAcrossChaptersNormalizesSignedCdnPathByAudioFileName() {
        assertTrue(
            MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                listOf(
                    "media-chapter:3" to "http://car-er.kuwo.cn/a/one/resource/30106/trackmedia/M5000004Gmy54cGDqK.mp3?token=one",
                    "media-chapter:4" to "http://car-er.kuwo.cn/b/two/resource/30106/trackmedia/M5000004Gmy54cGDqK.mp3?token=two"
                )
            )
        )
    }

    @Test
    fun repeatedAudioUrlAcrossChaptersAllowsDistinctSignedAudioFiles() {
        assertFalse(
            MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                listOf(
                    "media-chapter:1" to "http://car-er.kuwo.cn/a/resource/trackmedia/O400001CEm452y2LPh_1.ogg?token=one",
                    "media-chapter:2" to "http://car-er.kuwo.cn/b/resource/trackmedia/O400003mBxsU2pfLF8_1.ogg?token=two"
                )
            )
        )
    }
}
