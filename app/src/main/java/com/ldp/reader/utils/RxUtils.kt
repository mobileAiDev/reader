package com.ldp.reader.utils

import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.Single
import io.reactivex.SingleSource
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

/**
 * Created by ldp on 17-4-29.
 */
object RxUtils {
    @JvmStatic
    fun <T> toSimpleSingle(upstream: Single<T>): SingleSource<T> {
        return upstream.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    @JvmStatic
    fun <T> toSimpleSingle(upstream: Observable<T>): ObservableSource<T> {
        return upstream.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    @JvmStatic
    fun <T, R> twoTuple(first: T, second: R): TwoTuple<T, R> {
        return TwoTuple(first, second)
    }

    class TwoTuple<A, B>(
        @JvmField val first: A,
        @JvmField val second: B
    )
}
