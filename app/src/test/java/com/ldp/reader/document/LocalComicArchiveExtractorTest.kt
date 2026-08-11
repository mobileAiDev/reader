package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalComicArchiveExtractorTest {
    @Test
    fun defaultLimitsMatchTheBoundedMobileExtractionBudget() {
        val limits = ComicArchiveLimits()

        assertEquals(128L * 1024L * 1024L, limits.maxArchiveBytes)
        assertEquals(32L * 1024L * 1024L, limits.maxImageBytes)
        assertEquals(256L * 1024L * 1024L, limits.maxTotalImageBytes)
        assertEquals(16_000_000L, limits.maxImagePixels)
        assertEquals(1_024, limits.maxEntryNameBytes)
    }

    @Test
    fun storagePolicyRequiresSpaceForTheArchiveAndExtractedImagesTogether() {
        val limits = ComicArchiveLimits()
        val requiredBytes = 384L * 1024L * 1024L

        assertEquals(requiredBytes, ComicArchiveStoragePolicy.requiredWorkingBytes(limits))
        assertTrue(ComicArchiveStoragePolicy.hasEnoughSpace(requiredBytes, limits))
        assertTrue(!ComicArchiveStoragePolicy.hasEnoughSpace(requiredBytes - 1, limits))
    }

    @Test
    fun storagePolicySaturatesAnOverflowingWorkingSpaceBudget() {
        val limits = ComicArchiveLimits(
            maxArchiveBytes = Long.MAX_VALUE,
            maxTotalImageBytes = 1L
        )

        assertEquals(Long.MAX_VALUE, ComicArchiveStoragePolicy.requiredWorkingBytes(limits))
        assertTrue(ComicArchiveStoragePolicy.hasEnoughSpace(Long.MAX_VALUE, limits))
        assertTrue(!ComicArchiveStoragePolicy.hasEnoughSpace(Long.MAX_VALUE - 1L, limits))
    }

    @Test
    fun cachePolicyDeletesOnlyOtherDirectoriesAtLeastTwentyFourHoursOld() {
        val nowMillis = 2L * ComicArchiveCachePolicy.STALE_AFTER_MILLIS
        val boundary = nowMillis - ComicArchiveCachePolicy.STALE_AFTER_MILLIS

        assertTrue(
            ComicArchiveCachePolicy.shouldDeleteDirectory(
                candidateName = "00000000-0000-0000-0000-000000000001",
                candidatePath = "/cache/local-comic/00000000-0000-0000-0000-000000000001",
                currentPath = "/cache/local-comic/current",
                lastModifiedMillis = boundary,
                nowMillis = nowMillis
            )
        )
        assertTrue(
            !ComicArchiveCachePolicy.shouldDeleteDirectory(
                candidateName = "00000000-0000-0000-0000-000000000002",
                candidatePath = "/cache/local-comic/00000000-0000-0000-0000-000000000002",
                currentPath = "/cache/local-comic/current",
                lastModifiedMillis = boundary + 1L,
                nowMillis = nowMillis
            )
        )
        assertTrue(
            !ComicArchiveCachePolicy.shouldDeleteDirectory(
                candidateName = "00000000-0000-0000-0000-000000000003",
                candidatePath = "/cache/local-comic/00000000-0000-0000-0000-000000000003",
                currentPath = "/cache/local-comic/00000000-0000-0000-0000-000000000003",
                lastModifiedMillis = boundary - 1L,
                nowMillis = nowMillis
            )
        )
        assertTrue(
            !ComicArchiveCachePolicy.shouldDeleteDirectory(
                candidateName = "00000000-0000-0000-0000-000000000004",
                candidatePath = "/cache/local-comic/00000000-0000-0000-0000-000000000004",
                currentPath = "/cache/local-comic/current",
                lastModifiedMillis = nowMillis + 1L,
                nowMillis = nowMillis
            )
        )
        assertTrue(
            !ComicArchiveCachePolicy.shouldDeleteDirectory(
                candidateName = "00000000-0000-0000-0000-000000000005",
                candidatePath = "/cache/local-comic/00000000-0000-0000-0000-000000000005",
                currentPath = "/cache/local-comic/current",
                lastModifiedMillis = 0L,
                nowMillis = nowMillis
            )
        )
        assertTrue(
            !ComicArchiveCachePolicy.shouldDeleteDirectory(
                candidateName = "not-a-reader-cache-directory",
                candidatePath = "/cache/local-comic/not-a-reader-cache-directory",
                currentPath = "/cache/local-comic/current",
                lastModifiedMillis = boundary - 1L,
                nowMillis = nowMillis
            )
        )
    }

    @Test
    fun extractsImagesInNaturalOrderWithoutCollidingBasenames() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-order").toFile()
        try {
            val archive = archive(
                "10.png" to VALID_PNG,
                "2.png" to VALID_PNG,
                "1.png" to VALID_PNG,
                "nested/1.png" to VALID_PNG
            )

            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Success)
            val pages = (result as ComicArchiveExtractionResult.Success).pages
            assertEquals(listOf("1.png", "2.png", "10.png", "nested/1.png"), pages.map { it.sourceEntryName })
            assertEquals(4, pages.map { it.file.canonicalPath }.toSet().size)
            assertTrue(pages.all { it.file.isFile })
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.extension == "zip" })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlipEntriesWithoutWritingOutsideTheOutputDirectory() {
        val parentDirectory = Files.createTempDirectory("comic-extractor-slip").toFile()
        val outputDirectory = parentDirectory.resolve("pages")
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive("../escape.png" to VALID_PNG)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.UNSAFE_ENTRY, failure.reason)
            assertEquals("压缩包包含不安全路径：../escape.png", failure.message)
            assertTrue(!parentDirectory.resolve("escape.png").exists())
        } finally {
            parentDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchivesThatExceedTheEntryCountLimit() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-count").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxEntries = 2)
            ).extract(
                input = ByteArrayInputStream(
                    archive(
                        "metadata-1.txt" to byteArrayOf(1),
                        "metadata-2.txt" to byteArrayOf(2),
                        "1.png" to VALID_PNG
                    )
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("压缩包条目数量超过安全上限（2）", failure.message)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsEntryNamesThatExceedTheUtf8ByteLimit() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-name-size").toFile()
        try {
            val oversizedName = "章".repeat(341) + ".png"
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive(oversizedName to VALID_PNG)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("压缩包条目名称超过安全上限（1024 字节）", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun cancellationPropagatesAndRemovesPartialExtractionOutput() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-cancel").toFile()
        val expected = CancellationException("cancel extraction")
        var checks = 0
        try {
            try {
                LocalComicArchiveExtractor().extract(
                    input = ByteArrayInputStream(archive("1.png" to VALID_PNG)),
                    outputDirectory = outputDirectory,
                    checkCancelled = {
                        checks++
                        val partialPage = outputDirectory.listFiles().orEmpty()
                            .firstOrNull { it.name.startsWith("page-") }
                        if (partialPage != null && partialPage.length() > 0L) throw expected
                    }
                )
                fail("Expected extraction cancellation")
            } catch (error: CancellationException) {
                assertTrue(error === expected)
            }

            assertTrue(checks > 0)
            assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun stopsReadingAndDeletesTheTemporaryArchiveWhenRawInputExceedsTheLimit() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-raw-size").toFile()
        try {
            val input = CountingInputStream(
                ByteArrayInputStream(archive("1.png" to VALID_PNG))
            )
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxArchiveBytes = 64)
            ).extract(
                input = input,
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("漫画压缩包大小超过安全上限", failure.message)
            assertEquals(65L, input.bytesRead)
            assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAnImageThatExceedsTheSinglePageLimit() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-single-size").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxImageBytes = VALID_PNG.size.toLong() - 1)
            ).extract(
                input = ByteArrayInputStream(archive("large.png" to VALID_PNG)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("图片 large.png 超过单页解压上限", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsASmallPngThatDeclaresAnUnsafeCanvasSize() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-png-dimensions").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(
                    archive("huge.png" to pngWithDimensions(width = 100_000, height = 100_000))
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("图片 huge.png 的尺寸超过安全上限（100000×100000）", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAPngWhoseIdatStreamIsInvalidEvenWhenChunkAndZipCrcsAreValid() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-png-idat").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(
                    archive("broken.png" to pngWithInvalidIdatAndValidChunkCrc())
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_IMAGE, failure.reason)
            assertEquals("图片文件损坏：broken.png", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAPngWhoseChunkCrcIsInvalidEvenWhenTheZipCrcIsValid() {
        assertInvalidPngRejected(
            image = pngWithCorruptIdatChunkCrc(),
            entryName = "bad-chunk-crc.png"
        )
    }

    @Test
    fun rejectsPngsWithIncompleteExtraOrInvalidFilteredScanlines() {
        listOf(
            "short-scanline.png" to byteArrayOf(0, 0),
            "extra-scanline.png" to byteArrayOf(0, 0, 0, 0),
            "invalid-filter.png" to byteArrayOf(5, 0, 0)
        ).forEach { (entryName, scanline) ->
            assertInvalidPngRejected(
                image = pngWithRecompressedIdat(scanline),
                entryName = entryName
            )
        }
    }

    @Test
    fun rejectsAPlteChunkForGrayscaleColorModes() {
        assertInvalidPngRejected(
            image = insertPngChunkAfterIhdr(
                VALID_PNG,
                pngChunk("PLTE", byteArrayOf(0, 0, 0))
            ),
            entryName = "grayscale-with-palette.png"
        )
    }

    @Test
    fun rejectsAnIndexedPaletteLargerThanItsBitDepthAllows() {
        val indexed = VALID_PNG.copyOf().also { png ->
            png[24] = 1
            png[25] = 3
            val ihdrCrc = java.util.zip.CRC32().apply { update(png, 12, 17) }.value.toInt()
            writeBigEndianInt(png, offset = 29, value = ihdrCrc)
        }
        val withOversizedPalette = insertPngChunkAfterIhdr(
            indexed,
            pngChunk("PLTE", ByteArray(3 * 3))
        )
        assertInvalidPngRejected(
            image = replacePngIdat(withOversizedPalette, byteArrayOf(0, 0)),
            entryName = "indexed-palette-overflow.png"
        )
    }

    @Test
    fun rejectsAnImageWhosePixelCountExceedsTheConfiguredLimit() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-pixel-count").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(
                    maxImageDimension = 8_192,
                    maxImagePixels = 24_999_999
                )
            ).extract(
                input = ByteArrayInputStream(
                    archive("too-many-pixels.png" to pngWithDimensions(width = 5_000, height = 5_000))
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("图片 too-many-pixels.png 的尺寸超过安全上限（5000×5000）", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAJpegThatDeclaresAnUnsafeDimension() {
        assertUnsafeDimensionsRejected(
            entryName = "huge.jpg",
            image = jpegWithDimensions(width = 9_000, height = 1),
            width = 9_000,
            height = 1
        )
    }

    @Test
    fun rejectsUnsafeDimensionsInEverySupportedWebpHeader() {
        listOf(
            "lossy.webp" to lossyWebpWithDimensions(width = 9_000, height = 1),
            "lossless.webp" to losslessWebpWithDimensions(width = 9_000, height = 1),
            "extended.webp" to extendedWebpWithDimensions(width = 9_000, height = 1)
        ).forEach { (entryName, image) ->
            assertUnsafeDimensionsRejected(
                entryName = entryName,
                image = image,
                width = 9_000,
                height = 1
            )
        }
    }

    @Test
    fun rejectsAGifThatDeclaresAnUnsafeDimension() {
        assertUnsafeDimensionsRejected(
            entryName = "huge.gif",
            image = gifWithDimensions(width = 9_000, height = 1),
            width = 9_000,
            height = 1
        )
    }

    @Test
    fun rejectsAGifWhoseFrameIsUnsafeEvenWhenItsCanvasIsSmall() {
        assertUnsafeDimensionsRejected(
            entryName = "huge-frame.gif",
            image = gifWithFrameDimensions(width = 9_000, height = 9_000),
            width = 9_000,
            height = 9_000
        )
    }

    @Test
    fun extractsAValidMultiFrameGifWithExtensionsAndSubBlocks() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-multi-frame-gif").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxGifBlocks = 10)
            ).extract(
                input = ByteArrayInputStream(archive("animated.gif" to multiFrameGif())),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Success)
            assertEquals(1, (result as ComicArchiveExtractionResult.Success).pages.size)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafePixelCountInALaterGifFrame() {
        assertUnsafeDimensionsRejected(
            entryName = "unsafe-second-frame.gif",
            image = gifWithFrameDimensions(
                image = multiFrameGif(),
                frameIndex = 1,
                width = 6_000,
                height = 6_000
            ),
            width = 6_000,
            height = 6_000
        )
    }

    @Test
    fun rejectsTheFrameAfterTheConfiguredGifFrameLimit() {
        assertGifLimitExceeded(
            entryName = "too-many-frames.gif",
            image = multiFrameGif(),
            limits = ComicArchiveLimits(maxGifFrames = 1)
        )
    }

    @Test
    fun rejectsTheBlockAfterTheConfiguredGifBlockLimit() {
        assertGifLimitExceeded(
            entryName = "too-many-blocks.gif",
            image = multiFrameGif(),
            limits = ComicArchiveLimits(maxGifBlocks = 9)
        )
    }

    @Test
    fun rejectsGifFramesWhoseCumulativePixelsExceedTheLimit() {
        assertGifLimitExceeded(
            entryName = "too-many-frame-pixels.gif",
            image = multiFrameGif(),
            limits = ComicArchiveLimits(maxTotalGifFramePixels = 1)
        )
    }

    @Test
    fun extractsAGifWithALocalColorTable() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-local-gif-color-table").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxGifBlocks = 3)
            ).extract(
                input = ByteArrayInputStream(archive("local-color-table.gif" to gifWithLocalColorTable())),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Success)
            assertEquals(1, (result as ComicArchiveExtractionResult.Success).pages.size)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun countsALocalColorTableAgainstTheGifBlockLimit() {
        assertGifLimitExceeded(
            entryName = "local-color-table.gif",
            image = gifWithLocalColorTable(),
            limits = ComicArchiveLimits(maxGifBlocks = 2)
        )
    }

    @Test
    fun rejectsAFrameRectangleOutsideTheLogicalScreenAsInvalidImage() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-gif-frame-rectangle").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(
                    archive(
                        "outside.gif" to gifWithFrameRectangle(
                            left = 1,
                            top = 0,
                            width = 1,
                            height = 1
                        )
                    )
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_IMAGE, failure.reason)
            assertEquals("图片文件损坏：outside.gif", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun removesPreviouslyExtractedPagesAfterAGifBudgetFailure() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-gif-budget-cleanup").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxGifFrames = 1)
            ).extract(
                input = ByteArrayInputStream(
                    archive(
                        "1.png" to VALID_PNG,
                        "2.gif" to multiFrameGif()
                    )
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            assertEquals(
                ComicArchiveFailureReason.LIMIT_EXCEEDED,
                (result as ComicArchiveExtractionResult.Failure).reason
            )
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAGifWithTruncatedImageDataAsInvalidImage() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-truncated-gif").toFile()
        try {
            val truncatedImageData = VALID_GIF.copyOf().also { it[36] = 20 }
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive("truncated.gif" to truncatedImageData)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_IMAGE, failure.reason)
            assertEquals("图片文件损坏：truncated.gif", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAGifWithATruncatedExtensionSubBlockAsInvalidImage() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-truncated-gif-extension").toFile()
        try {
            val truncatedExtension = VALID_GIF.copyOfRange(0, 25) +
                byteArrayOf(0x21, 0xfe.toByte(), 0x0a, 'a'.code.toByte(), 0x3b)
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive("truncated-extension.gif" to truncatedExtension)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_IMAGE, failure.reason)
            assertEquals("图片文件损坏：truncated-extension.gif", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun extractsSupportedSmallImagesAfterInspectingTheirDimensions() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-formats").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(
                    archive(
                        "1.jpg" to VALID_JPEG,
                        "2.png" to VALID_PNG,
                        "3.webp" to VALID_WEBP,
                        "4.gif" to VALID_GIF
                    )
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Success)
            assertEquals(4, (result as ComicArchiveExtractionResult.Success).pages.size)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchivesThatExceedTheTotalUncompressedImageLimit() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-total-size").toFile()
        try {
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(
                    maxTotalImageBytes = VALID_PNG.size.toLong() * 2 - 1
                )
            ).extract(
                input = ByteArrayInputStream(
                    archive(
                        "1.png" to VALID_PNG,
                        "2.png" to VALID_PNG
                    )
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("漫画图片总解压大小超过安全上限", failure.message)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsHighlyCompressedImageEntries() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-ratio").toFile()
        try {
            val compressibleImage = VALID_PNG + ByteArray(8 * 1024)
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(maxCompressionRatio = 2.0)
            ).extract(
                input = ByteArrayInputStream(archive("bomb.png" to compressibleImage)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("图片 bomb.png 的压缩比超过安全上限", failure.message)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun enforcesTheSinglePageLimitAgainstForgedZipMetadata() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-forged-size").toFile()
        try {
            val forgedArchive = withCentralDirectoryUncompressedSize(
                archive("forged.png" to (VALID_PNG + ByteArray(4 * 1024))),
                uncompressedSize = 1
            )
            val result = LocalComicArchiveExtractor(
                limits = ComicArchiveLimits(
                    maxImageBytes = 128,
                    maxCompressionRatio = Double.MAX_VALUE
                )
            ).extract(
                input = ByteArrayInputStream(forgedArchive),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("图片 forged.png 超过单页解压上限", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun reportsAnEmptyArchiveClearly() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-empty").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive()),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.NO_IMAGES, failure.reason)
            assertEquals("压缩包中没有可读取的图片", failure.message)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun reportsAnInvalidImageClearlyAndRemovesPartialOutput() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-invalid-image").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(
                    archive(
                        "1.png" to VALID_PNG,
                        "2.png" to "not an image".toByteArray()
                    )
                ),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_IMAGE, failure.reason)
            assertEquals("图片文件损坏：2.png", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun reportsACorruptArchiveClearly() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-corrupt").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream("this is not a zip archive".toByteArray()),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_ARCHIVE, failure.reason)
            assertEquals("漫画压缩包损坏或格式不正确", failure.message)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rejectsAnArchiveWhoseImageChecksumIsCorrupt() {
        val outputDirectory = Files.createTempDirectory("comic-extractor-crc").toFile()
        try {
            val corruptArchive = withCorruptCentralDirectoryCrc(
                archive("1.png" to VALID_PNG)
            )
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(corruptArchive),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_ARCHIVE, failure.reason)
            assertEquals("漫画压缩包损坏或格式不正确", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
    }

    private fun withCentralDirectoryUncompressedSize(archive: ByteArray, uncompressedSize: Int): ByteArray {
        val copy = archive.copyOf()
        val index = centralDirectoryIndex(copy)
        repeat(4) { byteIndex ->
            copy[index + 24 + byteIndex] = (uncompressedSize ushr (8 * byteIndex)).toByte()
        }
        return copy
    }

    private fun withCorruptCentralDirectoryCrc(archive: ByteArray): ByteArray {
        val copy = archive.copyOf()
        val index = centralDirectoryIndex(copy)
        copy[index + 16] = (copy[index + 16].toInt() xor 0x01).toByte()
        return copy
    }

    private fun centralDirectoryIndex(archive: ByteArray): Int {
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        return archive.indices.firstOrNull { start ->
            start + signature.size <= archive.size &&
                signature.indices.all { offset -> archive[start + offset] == signature[offset] }
        } ?: error("Central directory header not found")
    }

    private fun pngWithDimensions(width: Int, height: Int): ByteArray {
        val copy = VALID_PNG.copyOf()
        writeBigEndianInt(copy, offset = 16, value = width)
        writeBigEndianInt(copy, offset = 20, value = height)
        val crc = java.util.zip.CRC32().apply { update(copy, 12, 17) }.value.toInt()
        writeBigEndianInt(copy, offset = 29, value = crc)
        return copy
    }

    private fun pngWithInvalidIdatAndValidChunkCrc(): ByteArray {
        val copy = VALID_PNG.copyOf()
        val typeOffset = copy.indexOfAscii("IDAT")
        val dataLength = ((copy[typeOffset - 4].toInt() and 0xff) shl 24) or
            ((copy[typeOffset - 3].toInt() and 0xff) shl 16) or
            ((copy[typeOffset - 2].toInt() and 0xff) shl 8) or
            (copy[typeOffset - 1].toInt() and 0xff)
        copy[typeOffset + 4] = 0
        val crc = java.util.zip.CRC32().apply {
            update(copy, typeOffset, 4 + dataLength)
        }.value.toInt()
        writeBigEndianInt(copy, offset = typeOffset + 4 + dataLength, value = crc)
        return copy
    }

    private fun pngWithCorruptIdatChunkCrc(): ByteArray {
        val copy = VALID_PNG.copyOf()
        val typeOffset = copy.indexOfAscii("IDAT")
        val dataLength = readBigEndianInt(copy, typeOffset - 4)
        val crcOffset = typeOffset + 4 + dataLength
        copy[crcOffset] = (copy[crcOffset].toInt() xor 0x01).toByte()
        return copy
    }

    private fun pngWithRecompressedIdat(scanline: ByteArray): ByteArray {
        return replacePngIdat(VALID_PNG, scanline)
    }

    private fun replacePngIdat(png: ByteArray, scanline: ByteArray): ByteArray {
        val typeOffset = png.indexOfAscii("IDAT")
        val dataLength = readBigEndianInt(png, typeOffset - 4)
        val chunkStart = typeOffset - 4
        val chunkEnd = typeOffset + 4 + dataLength + 4
        val compressed = ByteArrayOutputStream().use { bytes ->
            DeflaterOutputStream(bytes).use { it.write(scanline) }
            bytes.toByteArray()
        }
        return png.copyOfRange(0, chunkStart) +
            pngChunk("IDAT", compressed) +
            png.copyOfRange(chunkEnd, png.size)
    }

    private fun insertPngChunkAfterIhdr(png: ByteArray, chunk: ByteArray): ByteArray {
        val ihdrEnd = 8 + 4 + 4 + 13 + 4
        return png.copyOfRange(0, ihdrEnd) + chunk + png.copyOfRange(ihdrEnd, png.size)
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4)
        val result = ByteArray(4 + 4 + payload.size + 4)
        writeBigEndianInt(result, 0, payload.size)
        typeBytes.copyInto(result, destinationOffset = 4)
        payload.copyInto(result, destinationOffset = 8)
        val crc = java.util.zip.CRC32().apply {
            update(result, 4, 4 + payload.size)
        }.value.toInt()
        writeBigEndianInt(result, 8 + payload.size, crc)
        return result
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun assertInvalidPngRejected(image: ByteArray, entryName: String) {
        val outputDirectory = Files.createTempDirectory("comic-extractor-invalid-png").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive(entryName to image)),
                outputDirectory = outputDirectory
            )

            assertTrue(result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.INVALID_IMAGE, failure.reason)
            assertEquals("图片文件损坏：$entryName", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private fun writeBigEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { byteIndex ->
            bytes[offset + byteIndex] = (value ushr (24 - byteIndex * 8)).toByte()
        }
    }

    private fun assertUnsafeDimensionsRejected(
        entryName: String,
        image: ByteArray,
        width: Int,
        height: Int
    ) {
        val outputDirectory = Files.createTempDirectory("comic-extractor-dimensions").toFile()
        try {
            val result = LocalComicArchiveExtractor().extract(
                input = ByteArrayInputStream(archive(entryName to image)),
                outputDirectory = outputDirectory
            )

            assertTrue("Expected $entryName to be rejected", result is ComicArchiveExtractionResult.Failure)
            val failure = result as ComicArchiveExtractionResult.Failure
            assertEquals(ComicArchiveFailureReason.LIMIT_EXCEEDED, failure.reason)
            assertEquals("图片 $entryName 的尺寸超过安全上限（$width×$height）", failure.message)
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private fun assertGifLimitExceeded(
        entryName: String,
        image: ByteArray,
        limits: ComicArchiveLimits
    ) {
        val outputDirectory = Files.createTempDirectory("comic-extractor-gif-limit").toFile()
        try {
            val result = LocalComicArchiveExtractor(limits).extract(
                input = ByteArrayInputStream(archive(entryName to image)),
                outputDirectory = outputDirectory
            )

            assertTrue("Expected $entryName to exceed a GIF limit", result is ComicArchiveExtractionResult.Failure)
            assertEquals(
                ComicArchiveFailureReason.LIMIT_EXCEEDED,
                (result as ComicArchiveExtractionResult.Failure).reason
            )
            assertTrue(outputDirectory.listFiles().orEmpty().none { it.name.startsWith("page-") })
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private fun jpegWithDimensions(width: Int, height: Int): ByteArray {
        val copy = VALID_JPEG.copyOf()
        val frameMarker = copy.indices.first { index ->
            index + 8 < copy.size &&
                copy[index].toInt() and 0xff == 0xff &&
                copy[index + 1].toInt() and 0xff in JPEG_FRAME_MARKERS
        }
        writeBigEndianShort(copy, frameMarker + 5, height)
        writeBigEndianShort(copy, frameMarker + 7, width)
        return copy
    }

    private fun lossyWebpWithDimensions(width: Int, height: Int): ByteArray {
        val copy = VALID_WEBP.copyOf()
        val chunkIndex = copy.indexOfAscii("VP8 ")
        writeLittleEndianShort(copy, chunkIndex + 14, width)
        writeLittleEndianShort(copy, chunkIndex + 16, height)
        return copy
    }

    private fun losslessWebpWithDimensions(width: Int, height: Int): ByteArray {
        val adjustedWidth = width - 1
        val adjustedHeight = height - 1
        val payload = byteArrayOf(
            0x2f,
            adjustedWidth.toByte(),
            ((adjustedWidth ushr 8) or ((adjustedHeight and 0x03) shl 6)).toByte(),
            (adjustedHeight ushr 2).toByte(),
            (adjustedHeight ushr 10).toByte()
        )
        return webpChunk("VP8L", payload)
    }

    private fun extendedWebpWithDimensions(width: Int, height: Int): ByteArray {
        val payload = ByteArray(10)
        writeLittleEndian24(payload, offset = 4, value = width - 1)
        writeLittleEndian24(payload, offset = 7, value = height - 1)
        return webpChunk("VP8X", payload)
    }

    private fun webpChunk(type: String, payload: ByteArray): ByteArray {
        val paddedPayloadSize = payload.size + payload.size % 2
        return ByteArray(20 + paddedPayloadSize).also { bytes ->
            "RIFF".toByteArray().copyInto(bytes, destinationOffset = 0)
            writeLittleEndianInt(bytes, offset = 4, value = bytes.size - 8)
            "WEBP".toByteArray().copyInto(bytes, destinationOffset = 8)
            type.toByteArray().copyInto(bytes, destinationOffset = 12)
            writeLittleEndianInt(bytes, offset = 16, value = payload.size)
            payload.copyInto(bytes, destinationOffset = 20)
        }
    }

    private fun gifWithDimensions(width: Int, height: Int): ByteArray {
        return VALID_GIF.copyOf().also { copy ->
            writeLittleEndianShort(copy, offset = 6, value = width)
            writeLittleEndianShort(copy, offset = 8, value = height)
        }
    }

    private fun gifWithFrameDimensions(width: Int, height: Int): ByteArray {
        return gifWithFrameDimensions(
            image = VALID_GIF,
            frameIndex = 0,
            width = width,
            height = height
        )
    }

    private fun gifWithFrameDimensions(
        image: ByteArray,
        frameIndex: Int,
        width: Int,
        height: Int
    ): ByteArray {
        return image.copyOf().also { copy ->
            val descriptorOffset = copy.indices
                .filter { copy[it].toInt() and 0xff == 0x2c }
                .getOrNull(frameIndex)
                ?: error("GIF frame $frameIndex not found")
            writeLittleEndianShort(copy, offset = descriptorOffset + 5, value = width)
            writeLittleEndianShort(copy, offset = descriptorOffset + 7, value = height)
        }
    }

    private fun gifWithFrameRectangle(left: Int, top: Int, width: Int, height: Int): ByteArray {
        return VALID_GIF.copyOf().also { copy ->
            writeLittleEndianShort(copy, offset = 26, value = left)
            writeLittleEndianShort(copy, offset = 28, value = top)
            writeLittleEndianShort(copy, offset = 30, value = width)
            writeLittleEndianShort(copy, offset = 32, value = height)
        }
    }

    private fun gifWithLocalColorTable(): ByteArray {
        val headerAndLogicalScreen = VALID_GIF.copyOfRange(0, 13).also { it[10] = 0x00 }
        val imageDescriptor = VALID_GIF.copyOfRange(25, 35).also { it[9] = 0x80.toByte() }
        val localColorTable = VALID_GIF.copyOfRange(13, 19)
        val imageData = VALID_GIF.copyOfRange(35, VALID_GIF.lastIndex)
        return headerAndLogicalScreen + imageDescriptor + localColorTable + imageData + byteArrayOf(0x3b)
    }

    private fun multiFrameGif(): ByteArray {
        val prefixThroughColorTable = VALID_GIF.copyOfRange(0, 25).also { it[4] = '9'.code.toByte() }
        val imageBlock = VALID_GIF.copyOfRange(25, VALID_GIF.lastIndex)
        val commentExtension = byteArrayOf(
            0x21, 0xfe.toByte(),
            0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            0x02, 'd'.code.toByte(), 'e'.code.toByte(),
            0x00
        )
        val graphicControlExtension = byteArrayOf(
            0x21, 0xf9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        return prefixThroughColorTable +
            commentExtension + graphicControlExtension + imageBlock + imageBlock + byteArrayOf(0x3b)
    }

    private fun writeBigEndianShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeLittleEndianShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeLittleEndian24(bytes: ByteArray, offset: Int, value: Int) {
        repeat(3) { byteIndex ->
            bytes[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
        }
    }

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { byteIndex ->
            bytes[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
        }
    }

    private fun ByteArray.indexOfAscii(value: String): Int {
        val expected = value.toByteArray()
        return indices.first { start ->
            start + expected.size <= size &&
                expected.indices.all { offset -> this[start + offset] == expected[offset] }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            return super.read().also { if (it >= 0) bytesRead++ }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
    }

    private companion object {
        val JPEG_FRAME_MARKERS = setOf(
            0xc0, 0xc1, 0xc2, 0xc3,
            0xc5, 0xc6, 0xc7,
            0xc9, 0xca, 0xcb,
            0xcd, 0xce, 0xcf
        )
        val VALID_JPEG: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4n" +
                "ICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIy" +
                "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEB" +
                "AQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
                "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1" +
                "dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl" +
                "5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcF" +
                "BAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
                "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0" +
                "tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDi6KKK+ZP3" +
                "E//Z"
        )
        val VALID_WEBP: ByteArray = Base64.getDecoder().decode(
            "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoBAAEAAUAmJaACdLoB+AADsAD+8ut//NgVzXPv9//S4P0uD9Lg/9KQAAA="
        )
        val VALID_GIF: ByteArray = Base64.getDecoder().decode(
            "R0lGODdhAQABAIEAAP8AAAAAAAAAAAAAACwAAAAAAQABAAAIBAABBAQAOw=="
        )
        val VALID_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
