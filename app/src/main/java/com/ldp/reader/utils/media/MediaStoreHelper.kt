package com.ldp.reader.utils.media

import android.content.Context
import android.database.Cursor
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import java.io.File
import java.lang.ref.WeakReference

object MediaStoreHelper {
    @JvmStatic
    fun getAllBookFile(activity: FragmentActivity, resultCallback: MediaResultCallback) {
        activity.supportLoaderManager
            .initLoader(
                LoaderCreator.ALL_BOOK_FILE,
                null,
                MediaLoaderCallbacks(activity, resultCallback)
            )
    }

    interface MediaResultCallback {
        fun onResultCallback(files: List<File>)
    }

    class MediaLoaderCallbacks(
        context: Context,
        protected val mResultCallback: MediaResultCallback
    ) : LoaderManager.LoaderCallbacks<Cursor> {
        protected val mContext: WeakReference<Context> = WeakReference(context)

        override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
            return LoaderCreator.create(mContext.get()!!, id, args)
        }

        override fun onLoadFinished(loader: Loader<Cursor>, data: Cursor?) {
            val localFileLoader = loader as LocalFileLoader
            localFileLoader.parseData(data, mResultCallback)
        }

        override fun onLoaderReset(loader: Loader<Cursor>) {
        }
    }
}
