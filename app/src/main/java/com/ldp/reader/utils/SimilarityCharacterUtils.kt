package com.ldp.reader.utils

object SimilarityCharacterUtils {
    @JvmStatic
    fun main(args: Array<String>) {
        val str1 = "今天星期四"
        val str2 = "今天是星期五"
        getSimilarity(str1, str2)
    }

    @JvmStatic
    fun getSimilarity(str1: String, str2: String): Double {
        val len1 = str1.length
        val len2 = str2.length
        val dif = Array(len1 + 1) { IntArray(len2 + 1) }
        for (a in 0..len1) {
            dif[a][0] = a
        }
        for (a in 0..len2) {
            dif[0][a] = a
        }
        var temp: Int
        for (i in 1..len1) {
            for (j in 1..len2) {
                temp = if (str1[i - 1] == str2[j - 1]) 0 else 1
                dif[i][j] = min(
                    dif[i - 1][j - 1] + temp,
                    dif[i][j - 1] + 1,
                    dif[i - 1][j] + 1
                )
            }
        }
        println("字符串\"" + str1 + "\"与\"" + str2 + "\"的比较")
        println("差异步骤：" + dif[len1][len2])
        val similarity = 1 - dif[len1][len2].toFloat() / str1.length.coerceAtLeast(str2.length)
        println("相似度：$similarity")
        return similarity.toDouble()
    }

    private fun min(vararg values: Int): Int {
        var min = Int.MAX_VALUE
        for (value in values) {
            if (min > value) {
                min = value
            }
        }
        return min
    }
}
