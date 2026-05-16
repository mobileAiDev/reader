package com.ldp.reader.ui.base

import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable

/**
 * Created by ldp on 17-4-26.
 */
open class RxPresenter<T : BaseContract.BaseView> : BaseContract.BasePresenter<T> {
    @JvmField
    protected var mView: T? = null

    @JvmField
    protected var mDisposable: CompositeDisposable? = null

    protected fun unSubscribe() {
        if (mDisposable != null) {
            mDisposable!!.dispose()
        }
    }

    protected fun addDisposable(subscription: Disposable?) {
        if (mDisposable == null) {
            mDisposable = CompositeDisposable()
        }
        mDisposable!!.add(subscription!!)
    }

    override fun attachView(view: T) {
        mView = view
    }

    override fun detachView() {
        mView = null
        unSubscribe()
    }
}
