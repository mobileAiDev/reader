package com.ldp.reader.model.bean

import android.os.Parcel
import android.os.Parcelable
import kotlin.jvm.JvmName

class CollBookBean() : Parcelable {
    private var idValue: String? = null
    var _id: String?
        @JvmName("getIdValueForKotlin")
        get() = idValue
        @JvmName("setIdValueForKotlin")
        set(value) {
            idValue = value
        }
    var title: String? = null
    var author: String? = null
    var shortIntro: String? = null
    var cover: String? = null
    var bookStatus: String? = null
    var updated: String? = null
    var lastRead: String? = null
    var chaptersCount: Int = 0
    var lastChapter: String? = null
    private var isUpdate: Boolean = true
    private var isLocal: Boolean = false
    var bookIdInBiquge: String? = null
    private var bookChapterList: List<BookChapterBean>? = null
    var bookChapters: List<BookChapterBean>?
        @JvmName("getBookChaptersForKotlin")
        get() = bookChapterList
        @JvmName("setBookChaptersForKotlin")
        set(value) {
            setBookChapters(value)
        }

    private constructor(parcel: Parcel) : this() {
        idValue = parcel.readString()
        title = parcel.readString()
        author = parcel.readString()
        shortIntro = parcel.readString()
        cover = parcel.readString()
        bookStatus = parcel.readString()
        updated = parcel.readString()
        lastRead = parcel.readString()
        chaptersCount = parcel.readInt()
        lastChapter = parcel.readString()
        isUpdate = parcel.readByte().toInt() != 0
        isLocal = parcel.readByte().toInt() != 0
        bookIdInBiquge = parcel.readString()
    }

    constructor(
        _id: String?,
        title: String?,
        author: String?,
        shortIntro: String?,
        cover: String?,
        bookStatus: String?,
        updated: String?,
        lastRead: String?,
        chaptersCount: Int,
        lastChapter: String?,
        isUpdate: Boolean,
        isLocal: Boolean,
        bookIdInBiquge: String?
    ) : this() {
        this.idValue = _id
        this.title = title
        this.author = author
        this.shortIntro = shortIntro
        this.cover = cover
        this.bookStatus = bookStatus
        this.updated = updated
        this.lastRead = lastRead
        this.chaptersCount = chaptersCount
        this.lastChapter = lastChapter
        this.isUpdate = isUpdate
        this.isLocal = isLocal
        this.bookIdInBiquge = bookIdInBiquge
    }

    fun get_id(): String? = idValue

    fun set_id(_id: String?) {
        idValue = _id
    }

    fun isUpdate(): Boolean = isUpdate

    fun setUpdate(update: Boolean) {
        isUpdate = update
    }

    fun getIsUpdate(): Boolean = isUpdate

    fun setIsUpdate(isUpdate: Boolean) {
        this.isUpdate = isUpdate
    }

    fun isLocal(): Boolean = isLocal

    fun setLocal(local: Boolean) {
        isLocal = local
    }

    fun getIsLocal(): Boolean = isLocal

    fun setIsLocal(isLocal: Boolean) {
        this.isLocal = isLocal
    }

    fun setBookChapters(beans: List<BookChapterBean>?) {
        bookChapterList = beans
        if (bookChapterList == null) {
            return
        }
        for (bean in bookChapterList!!) {
            bean.bookId = get_id()
        }
    }

    fun getBookChapters(): List<BookChapterBean>? = bookChapterList

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(idValue)
        dest.writeString(title)
        dest.writeString(author)
        dest.writeString(shortIntro)
        dest.writeString(cover)
        dest.writeString(bookStatus)
        dest.writeString(updated)
        dest.writeString(lastRead)
        dest.writeInt(chaptersCount)
        dest.writeString(lastChapter)
        dest.writeByte(if (isUpdate) 1 else 0)
        dest.writeByte(if (isLocal) 1 else 0)
        dest.writeString(bookIdInBiquge)
    }

    override fun toString(): String {
        return "CollBookBean{" +
            "_id='" + idValue + '\'' +
            ", title='" + title + '\'' +
            ", author='" + author + '\'' +
            ", shortIntro='" + shortIntro + '\'' +
            ", cover='" + cover + '\'' +
            ", bookStatus='" + bookStatus + '\'' +
            ", updated='" + updated + '\'' +
            ", lastRead='" + lastRead + '\'' +
            ", chaptersCount=" + chaptersCount +
            ", lastChapter='" + lastChapter + '\'' +
            ", isUpdate=" + isUpdate +
            ", isLocal=" + isLocal +
            ", bookIdInBiquge='" + bookIdInBiquge + '\'' +
            ", bookChapterList=" + bookChapterList +
            '}'
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CollBookBean> = object : Parcelable.Creator<CollBookBean> {
            override fun createFromParcel(source: Parcel): CollBookBean {
                return CollBookBean(source)
            }

            override fun newArray(size: Int): Array<CollBookBean?> {
                return arrayOfNulls(size)
            }
        }
    }
}
