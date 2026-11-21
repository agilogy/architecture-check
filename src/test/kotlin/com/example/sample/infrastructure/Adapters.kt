package com.example.sample.infrastructure

import com.example.sample.domain.readsAFile
import com.example.sample.domain.usedInModule
import java.util.zip.CRC32

class UsesAnUncataloguedLibrary {
    fun checksum(): Long = CRC32().value + readsAFile("x").length + usedInModule()
}
