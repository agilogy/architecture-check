package com.example.app.infrastructure

import com.example.app.domain.Greeter
import com.example.sample.cycle.a.A
import com.example.sample.domain.Report
import com.example.sample.infrastructure.OnlyNeedsTheDomain
import com.example.sample.infrastructure.UsesAnUncataloguedLibrary

// Compiled into the `src/main` of the app `apps/app`.

object App {
    @JvmStatic
    fun main() {
        println(Report("Title").render())
        println(OnlyNeedsTheDomain().report().render() + UsesAnUncataloguedLibrary().checksum())
        println(A().b().a())
        println(Greeter().greet())
    }
}
