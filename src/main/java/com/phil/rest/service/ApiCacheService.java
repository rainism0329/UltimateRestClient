package com.phil.rest.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.XCollection;
import com.phil.rest.model.ApiDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责缓存 Live API 列表，避免每次启动都全量扫描
 */
@State(
        name = "UltimateRestApiCache",
        storages = @Storage("ultimate-rest-api-cache.xml")
)
public class ApiCacheService implements PersistentStateComponent<ApiCacheService.State> {

    public static class State {
        @XCollection(elementName = "api", style = XCollection.Style.v2)
        public List<ApiDefinition> cachedApis = new ArrayList<>();
    }

    private State myState = new State();

    public static ApiCacheService getInstance(Project project) {
        return project.getService(ApiCacheService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
    }

    public List<ApiDefinition> getCachedApis() {
        return myState.cachedApis;
    }

    public void updateCache(List<ApiDefinition> apis) {
        myState.cachedApis.clear();
        if (apis != null) {
            myState.cachedApis.addAll(apis);
        }
    }
}