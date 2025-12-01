package com.phil.rest.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 负责存储和提供 Header 的自动补全建议
 */
@Service(Service.Level.PROJECT)
@State(name = "UltimateRestHeaderStore", storages = [Storage("ultimate-rest-headers.xml")])
class HeaderStore : PersistentStateComponent<HeaderStore.State> {

    data class State(
        var customHeaders: MutableSet<String> = mutableSetOf()
    )

    private var myState = State()

    // 默认的常用 Headers
    private val defaultHeaders = listOf(
        "Accept", "Accept-Charset", "Accept-Encoding", "Accept-Language", "Authorization",
        "Cache-Control", "Connection", "Content-Length", "Content-Type", "Cookie",
        "Date", "Host", "Origin", "Pragma", "Referer", "User-Agent", "X-Requested-With"
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * 获取所有建议 (默认 + 用户自定义)
     */
    fun getAllSuggestions(): List<String> {
        val all = defaultHeaders.toMutableList()
        all.addAll(myState.customHeaders)
        return all.sorted()
    }

    /**
     * 记录一个新的 Header Key (如果它不在默认列表里)
     */
    fun recordHeader(key: String) {
        if (key.isNotBlank() && !defaultHeaders.contains(key)) {
            myState.customHeaders.add(key)
        }
    }

    companion object {
        fun getInstance(project: Project): HeaderStore = project.getService(HeaderStore::class.java)
    }
}