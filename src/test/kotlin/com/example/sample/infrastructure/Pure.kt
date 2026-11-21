package com.example.sample.infrastructure

import com.example.sample.domain.Report

class OnlyNeedsTheDomain {
    fun report(): Report = Report("pure")
}
