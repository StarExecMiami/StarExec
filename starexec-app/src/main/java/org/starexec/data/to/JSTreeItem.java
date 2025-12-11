package org.starexec.data.to;

import java.util.LinkedList;
import java.util.List;

public class JSTreeItem {
    private String data;
    private JSTreeAttribute attr;
    private List<JSTreeItem> children;
    private String state;

    public JSTreeItem(String name, int id, String state, String type) {
        this(name, id, state, type, 0, null);
    }

    public JSTreeItem(String name, int id, String state, String type, int maxStages) {
        this(name, id, state, type, maxStages, null);
    }

    public JSTreeItem(String name, int id, String state, String type, int maxStages, String cLass) {
        this.data = name;
        this.attr = new JSTreeAttribute(id, type, maxStages, cLass);
        this.state = state;
        this.children = new LinkedList<>();
    }

    public List<JSTreeItem> getChildren() {
        return children;
    }

    public void addChild(JSTreeItem child) {
        children.add(child);
    }
}
