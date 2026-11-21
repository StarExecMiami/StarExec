/* Translate the existing StarExec tree feeds at the jsTree boundary. */
star.treeNodes = function (items, lazy, distinguishQueues, parentId) {
  if (!Array.isArray(items)) {
    return false;
  }
  return items.filter(function (item) {
    return item && item.attr && Number.isInteger(item.attr.id) && typeof item.data === "string";
  }).map(function (item) {
    var attrs = item.attr;
    var id = String(attrs.id);
    var children = Array.isArray(item.children) && item.children.length;
    var nodeId = id;
    if (distinguishQueues) {
      nodeId = /queue$/.test(attrs.rel) ? "queue-" + id : "node-" + (parentId ? parentId + "-" : "") + id;
    }
    return {
      id: nodeId,
      text: item.data,
      li_attr: { rel: attrs.rel, "class": attrs.cLass || attrs["class"] || "" },
      data: { id: id, rel: attrs.rel, global: attrs.global, defaultQueueId: attrs.defaultQueueId, maxStages: attrs.maxStages },
      children: children ? star.treeNodes(item.children, lazy, distinguishQueues, id)
        : Boolean(lazy && item.state === "closed"),
      state: { opened: item.state === "open" },
    };
  });
};

star.selectTreeNode = function (tree, id) {
  if (id != null && tree.get_node(String(id)) && !tree.is_selected(String(id))) {
    tree.deselect_all(true);
    tree.select_node(String(id));
  }
};

star.openTreePath = function (tree, ids, done) {
  function open(index) {
    var id = ids[index];
    if (id == null || !tree.get_node(id)) {
      done(null);
    } else if (index === ids.length - 1) {
      done(id);
    } else {
      tree.open_node(id, function () {
        // An already-open parent reports false, but its children are available.
        open(index + 1);
      }, 0);
    }
  }
  open(0);
};

star.mountTree = function ($tree, source, options) {
  var core = {
    multiple: false,
    force_text: true,
    worker: false,
    animation: 200,
    themes: { name: "default-dark", dots: options.dots !== false },
    data: Array.isArray(source) ? star.treeNodes(source, false, options.queues) : function (node, callback) {
      var id = node.id === "#" ? options.rootId : node.data.id;
      var params = id == null ? {} : { id: id };
      $.ajax({ url: source, data: params, dataType: "json", cache: false }).done(function (items) {
        var nodes = star.treeNodes(items, true, options.queues, node.id === "#" ? null : id);
        callback(nodes);
        if (nodes === false) {
          showMessage("error", "Could not load tree items.", 5000);
        }
      }).fail(function () {
        callback(false);
        showMessage("error", "Could not load tree items.", 5000);
      });
    },
  };
  var settings = { core: core };
  var ready = "ready.jstree";
  if (options.stateKey) {
    settings.plugins = ["state"];
    settings.state = { key: "starexec-tree-" + starexecRoot + options.stateKey };
    ready = "state_ready.jstree";
  }
  $tree.on("select_node.jstree", function (event, data) {
    options.select(data.node, data.instance);
  }).one(ready, function (event, data) {
    var tree = data.instance;
    if (options.ready) {
      options.ready(tree);
    }
    if (!tree.get_selected().length) {
      star.selectTreeNode(tree, options.initial);
    }
  });
  if (options.selectParentOnClose) {
    $tree.on("close_node.jstree", function (event, data) {
      if (data.node.children_d.includes(data.instance.get_selected()[0])) {
        star.selectTreeNode(data.instance, data.node.id);
      }
    });
  }
  return $tree.jstree(settings);
};
