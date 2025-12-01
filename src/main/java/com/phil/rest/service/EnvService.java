package com.phil.rest.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.phil.rest.model.RestEnv;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@State(name = "UltimateRestEnvService", storages = @Storage("ultimate-rest-envs.xml"))
public class EnvService implements PersistentStateComponent<EnvService.State> {

    public static class State {
        public List<RestEnv> envs = new ArrayList<>();
        public String selectedEnvId; // 记住上次选的哪个环境
    }

    private State myState = new State();

    public static EnvService getInstance(Project project) {
        return project.getService(EnvService.class);
    }

    @Override
    public @Nullable State getState() { return myState; }

    @Override
    public void loadState(@NotNull State state) { this.myState = state; }

    // --- 业务方法 ---

    public List<RestEnv> getEnvs() { return myState.envs; }

    public void addEnv(RestEnv env) { myState.envs.add(env); }

    public void removeEnv(RestEnv env) { myState.envs.remove(env); }

    public RestEnv getSelectedEnv() {
        if (myState.selectedEnvId == null) return null;
        return myState.envs.stream()
                .filter(e -> e.getId().equals(myState.selectedEnvId))
                .findFirst().orElse(null);
    }

    public void setSelectedEnv(RestEnv env) {
        this.myState.selectedEnvId = (env == null) ? null : env.getId();
    }
}