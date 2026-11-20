function makeSpaceTree(selector, options) {
  var $chain = $(options.chain);
  var chain = String($chain.data("space-chain") || $chain.attr("value") || "")
    .split(",").map(function (id) { return id.trim(); }).filter(Boolean);
  return star.mountTree($(selector), starexecRoot + "services/space/subspaces", {
    rootId: -1,
    stateKey: chain.length ? undefined : options.stateKey,
    initial: chain.length ? undefined : "1",
    selectParentOnClose: true,
    select: options.select,
    ready: function (tree) {
      if (chain.length) {
        star.openTreePath(tree, chain, function (id) {
          if (!tree.get_selected().length) {
            star.selectTreeNode(tree, id || "1");
          }
        });
      }
    },
  });
}
