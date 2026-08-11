package com.ldp.reader.ui.activity

internal object ReaderBottomDialogWindowPolicy {
    fun fitInsetsTypesForEdgeToEdgeBottom(
        currentFitInsetsTypes: Int,
        navigationBarInsetsType: Int
    ): Int = currentFitInsetsTypes and navigationBarInsetsType.inv()
}
