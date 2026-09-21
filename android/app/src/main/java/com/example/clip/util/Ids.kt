package com.example.clip.util

import java.util.UUID

object Ids {
    fun newLocalId(): String = "loc_" + UUID.randomUUID().toString().replace("-", "")
    fun newBlobId(): String = "blob_" + UUID.randomUUID().toString().replace("-", "")
    fun newNoteId(): String = "note_" + UUID.randomUUID().toString().replace("-", "")
}
