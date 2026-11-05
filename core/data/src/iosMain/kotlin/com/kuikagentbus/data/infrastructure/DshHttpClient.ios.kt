package com.kuikagentbus.data.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createDshHttpClient(): HttpClient = HttpClient(Darwin) {
    installDshSseClient()
}
