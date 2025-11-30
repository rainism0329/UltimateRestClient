package com.phil.rest.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.phil.rest.model.CollectionNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// 定义存储的位置和文件名
@State(
        name = "UltimateRestCollectionService",
        storages = @Storage("ultimate-rest-collections.xml")
)
public class CollectionService implements PersistentStateComponent<CollectionService.State> {

    // 内部状态类
    public static class State {
        public List<CollectionNode> rootNodes = new ArrayList<>();
    }

    private State myState = new State();

    public static CollectionService getInstance(Project project) {
        return project.getService(CollectionService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
    }

    // --- 业务方法 ---

    public List<CollectionNode> getRootNodes() {
        return myState.rootNodes;
    }

    public void addRootNode(CollectionNode node) {
        myState.rootNodes.add(node);
    }

    // 获取一个默认的“Root”文件夹，如果没有就创建
    public CollectionNode getOrCreateDefaultRoot() {
        if (myState.rootNodes.isEmpty()) {
            myState.rootNodes.add(CollectionNode.createFolder("Default Collection"));
        }
        return myState.rootNodes.get(0);
    }
}