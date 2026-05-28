package com.bydmate.app.data.vehicle

class WriteAllowlist(private val map: Map<String, Any> = emptyMap()) {
    companion object {
        val EMPTY: WriteAllowlist = WriteAllowlist()
    }
}
