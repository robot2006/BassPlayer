package com.bassplayer

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long,
    val dateAdded: Long,
    val uri: Uri
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeString(title)
        dest.writeString(artist)
        dest.writeString(album)
        dest.writeLong(durationMs)
        dest.writeLong(albumId)
        dest.writeLong(dateAdded)
        dest.writeParcelable(uri, flags)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<Song> {
            override fun createFromParcel(parcel: Parcel): Song = Song(
                id = parcel.readLong(),
                title = parcel.readString().orEmpty(),
                artist = parcel.readString().orEmpty(),
                album = parcel.readString().orEmpty(),
                durationMs = parcel.readLong(),
                albumId = parcel.readLong(),
                dateAdded = parcel.readLong(),
                uri = parcel.readParcelable(Uri::class.java.classLoader) ?: Uri.EMPTY
            )

            override fun newArray(size: Int): Array<Song?> = arrayOfNulls(size)
        }
    }
}
