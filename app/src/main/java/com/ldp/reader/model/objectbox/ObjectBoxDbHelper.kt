package com.ldp.reader.model.objectbox

import com.ldp.reader.App
import io.objectbox.BoxStore

class ObjectBoxDbHelper private constructor() {
    val store: BoxStore = MyObjectBox.builder()
        .androidContext(App.getContext())
        .build()

    companion object {
        @Volatile
        private var sInstance: ObjectBoxDbHelper? = null

        @JvmStatic
        fun getInstance(): ObjectBoxDbHelper {
            if (sInstance == null) {
                synchronized(ObjectBoxDbHelper::class.java) {
                    if (sInstance == null) {
                        sInstance = ObjectBoxDbHelper()
                    }
                }
            }
            return sInstance!!
        }
    }
}
